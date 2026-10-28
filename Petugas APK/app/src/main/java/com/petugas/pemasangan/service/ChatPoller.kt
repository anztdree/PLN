package com.petugas.pemasangan.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.petugas.pemasangan.MainActivity
import com.petugas.pemasangan.R
import com.petugas.pemasangan.data.ConvSeenStore
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.chat.ChatShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * M8 — pemantau pesan baru TANPA notifikasi permanen.
 *
 * Dulu (M5–M7) pemantauan memakai foreground service, sehingga status bar
 * SELALU menampilkan tulisan "Pesan Petugas aktif" — mengganggu dan keliru:
 * yang diharapkan pengguna hanya notifikasi saat ada chat baru.
 *
 * Kini pemeriksaan berjalan di dalam proses aplikasi (tanpa ikon permanen):
 *  1. Selama proses aplikasi hidup (aplikasi terbuka / baru saja dipakai),
 *     pemeriksaan tiap 15 detik lewat GET /api/chat/summary — SATU permintaan
 *     ringkas untuk semua percakapan. Ada pesan baru dari orang lain ->
 *     notifikasi langsung muncul di status bar.
 *  2. Saat proses ditutup ponsel, [ChatPollWorker] (WorkManager) mengambil
 *     alih: pemeriksaan berkala tiap 15 menit, tahan reboot, tanpa
 *     notifikasi penanda apa pun.
 *
 * Satu-satunya notifikasi yang muncul = pesan baru. Ketuk notifikasi ->
 * langsung ke percakapan yang bersangkutan (deep link lewat MainActivity).
 */
object ChatPoller {

    private const val CHANNEL_PESAN = "chat_messages"
    private const val POLL_MS = 15_000L
    private const val WORK_NAME = "chat_poll"

    @Volatile
    private var loopJalan = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Nyalakan pemantauan (aman dipanggil berulang):
     * mulai putaran in-process + pastikan pekerjaan berkala terjadwal.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        startLoop(app)
        jadwalkanWork(app)
    }

    private fun startLoop(app: Context) {
        if (loopJalan) return
        loopJalan = true
        scope.launch {
            val settings = SettingsRepository(app)
            while (true) {
                try {
                    if (settings.isRegistered()) checkOnce(app)
                } catch (_: Exception) {
                    // jaringan bermasalah — coba lagi pada siklus berikutnya
                }
                delay(POLL_MS)
            }
        }
    }

    /** Pastikan pekerjaan berkala WorkManager terjadwal (fallback aplikasi tertutup). */
    private fun jadwalkanWork(app: Context) {
        runCatching {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ChatPollWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }

    /**
     * Satu putaran pemeriksaan (dipakai loop in-process & WorkManager).
     * Mutex -> loop & worker tidak pernah mengirim notifikasi ganda.
     * v1.16-m17: satu jadwal untuk dua pemantau — pesan obrolan DAN
     * catatan pusat atas data terkirim.
     */
    private val kunci = Mutex()

    suspend fun checkOnce(app: Context) {
        kunci.withLock {
            periksa(app)
            CatatanPoller.checkOnce(app)
        }
    }

    private suspend fun periksa(app: Context) {
        val settings = SettingsRepository(app)
        val deviceId = settings.deviceId()
        val androidId = settings.androidId()

        val hasil = PlnApi.fetchSummary(deviceId, androidId).getOrNull() ?: return
        val myId = hasil.meId.toString()

        // Perbarui badge lencana FAB & daftar obrolan (state Compose bersama).
        ChatShared.unreadTotal = hasil.unreadTotal
        ChatShared.summary = hasil

        for (conv in hasil.convs) {
            if (conv.lastId <= 0) continue

            if (!ConvSeenStore.has(app, conv.key)) {
                // Pertama kali pemantau melihat percakapan ini: tandai tanpa
                // notifikasi agar riwayat lama tidak membanjiri notifikasi.
                ConvSeenStore.set(app, conv.key, conv.lastId)
                continue
            }
            val sudah = ConvSeenStore.get(app, conv.key)
            if (conv.lastId <= sudah) continue

            // Ada pesan lebih baru dari catatan — tandai sudah dibaca…
            ConvSeenStore.set(app, conv.key, conv.lastId)

            val dariSaya = conv.lastSenderId == myId
            val sedangDibuka = ChatShared.activeConvKey == conv.key
            if (dariSaya || sedangDibuka || conv.lastBody.isBlank()) continue

            // …lalu beri notifikasi.
            munculkanNotifikasi(app, conv)
        }
    }

    private fun munculkanNotifikasi(context: Context, conv: PlnApi.SummaryConv) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        buatChannel(context)

        val judul = conv.title.ifBlank {
            if (conv.partner == "global") "Diskusi Petugas" else "Pesan baru"
        }
        val isi = when {
            conv.partner == "global" && conv.lastSenderNama.isNotBlank() ->
                "${conv.lastSenderNama}: ${conv.lastBody}"
            else -> conv.lastBody
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_PESAN)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(judul)
            .setContentText(isi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(isi))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(intentBuka(context, conv))
            .build()

        nm.notify(conv.key.hashCode(), notif)
    }

    /** Ketukan notifikasi membuka percakapan terkait langsung. */
    private fun intentBuka(context: Context, conv: PlnApi.SummaryConv): PendingIntent {
        val route = when {
            conv.partner == "global" -> "chat/global"
            conv.partner == "admin" -> "chat/admin"
            else -> "chat/private/${conv.partner}?nama=${URLEncoder.encode(conv.title, "UTF-8")}"
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            conv.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Channel pesan baru — pentingnya TINGGI agar muncul heads-up. */
    private fun buatChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PESAN,
                "Pesan Obrolan",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifikasi pesan baru dari petugas lain & admin"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
            }
        )
    }
}
