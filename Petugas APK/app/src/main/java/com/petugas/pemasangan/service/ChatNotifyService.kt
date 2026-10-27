package com.petugas.pemasangan.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.petugas.pemasangan.MainActivity
import com.petugas.pemasangan.R
import com.petugas.pemasangan.data.ConvSeenStore
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.chat.ChatShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * M5 — layanan latar pemantau pesan (foreground service).
 *
 * Berjalan terus setelah aplikasi dibuka sekali (dan setelah ponsel
 * dinyalakan, lewat [BootReceiver]) memeriksa pesan baru tiap 15 detik
 * lewat GET /api/chat/summary — SATU permintaan ringkas untuk semua
 * percakapan (umum, pusat, dan DM sesama petugas).
 *
 * Saat ada pesan baru dari orang lain dan percakapan itu TIDAK sedang
 * dibuka, munculkan notifikasi. Ketuk notifikasi -> langsung ke
 * percakapan yang bersangkutan (deep link lewat MainActivity).
 *
 * Tipe "specialUse" (Android 14+) dipilih karena layanan ini butuh
 * berjalan seharian tanpa batas 6 jam seperti tipe dataSync.
 */
class ChatNotifyService : Service() {

    private var loopJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        buatChannel()
        startForegroundCompat(notifLayanan())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { pollingLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        sedangJalan = false
        super.onDestroy()
    }

    /* ================= INTI ================= */

    private suspend fun pollingLoop() {
        val settings = SettingsRepository(applicationContext)
        while (true) {
            try {
                if (settings.isRegistered()) {
                    periksaPesanBaru(settings)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce // layanan dimatikan — hentikan loop
            } catch (_: Exception) {
                // jaringan bermasalah — coba lagi pada siklus berikutnya
            }
            delay(POLL_MS)
        }
    }

    private suspend fun periksaPesanBaru(settings: SettingsRepository) {
        val deviceId = settings.deviceId()
        val androidId = settings.androidId()

        val hasil = PlnApi.fetchSummary(deviceId, androidId).getOrNull() ?: return
        val myId = hasil.meId.toString()

        for (conv in hasil.convs) {
            if (conv.lastId <= 0) continue

            if (!ConvSeenStore.has(this, conv.key)) {
                // Pertama kali layanan melihat percakapan ini: tandai tanpa
                // notifikasi agar riwayat lama tidak membanjiri notifikasi.
                ConvSeenStore.set(this, conv.key, conv.lastId)
                continue
            }
            val sudah = ConvSeenStore.get(this, conv.key)
            if (conv.lastId <= sudah) continue

            // Ada pesan lebih baru dari catatan — tandai sudah dibaca…
            ConvSeenStore.set(this, conv.key, conv.lastId)

            val dariSaya = conv.lastSenderId == myId
            val sedangDibuka = ChatShared.activeConvKey == conv.key
            if (dariSaya || sedangDibuka || conv.lastBody.isBlank()) continue

            // …lalu beri notifikasi.
            munculkanNotifikasi(conv)
        }
    }

    private fun munculkanNotifikasi(conv: PlnApi.SummaryConv) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val judul = conv.title.ifBlank {
            if (conv.partner == "global") "Obrolan Umum" else "Pesan baru"
        }
        val isi = when {
            conv.partner == "global" && conv.lastSenderNama.isNotBlank() ->
                "${conv.lastSenderNama}: ${conv.lastBody}"
            else -> conv.lastBody
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_PESAN)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(judul)
            .setContentText(isi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(isi))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(intentBuka(conv))
            .build()

        nm.notify(conv.key.hashCode(), notif)
    }

    /** Ketukan notifikasi membuka percakapan terkait langsung. */
    private fun intentBuka(conv: PlnApi.SummaryConv): PendingIntent {
        val route = when {
            conv.partner == "global" -> "chat/global"
            conv.partner == "admin" -> "chat/admin"
            else -> "chat/private/${conv.partner}?nama=${URLEncoder.encode(conv.title, "UTF-8")}"
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            this,
            conv.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /* ================= NOTIFIKASI LAYANAN & CHANNEL ================= */

    private fun notifLayanan(): Notification =
        NotificationCompat.Builder(this, CHANNEL_LAYANAN)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("Pesan Petugas aktif")
            .setContentText("Menunggu pesan baru — ketuk untuk membuka aplikasi")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_LAYANAN_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_LAYANAN_ID, notif)
        }
    }

    private fun buatChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PESAN,
                "Pesan Obrolan",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifikasi pesan baru dari petugas lain & pusat"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LAYANAN,
                "Layanan Pesan",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Penanda layanan pemantau pesan berjalan"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_PESAN = "chat_messages"
        private const val CHANNEL_LAYANAN = "chat_service"
        private const val NOTIF_LAYANAN_ID = 41
        private const val POLL_MS = 15_000L

        @Volatile
        private var sedangJalan = false

        /** Mulai layanan (aman dipanggil berulang). */
        fun start(context: Context) {
            if (sedangJalan) return
            sedangJalan = true
            val intent = Intent(context, ChatNotifyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
