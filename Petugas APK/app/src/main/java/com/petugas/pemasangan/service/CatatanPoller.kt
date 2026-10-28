package com.petugas.pemasangan.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.petugas.pemasangan.MainActivity
import com.petugas.pemasangan.R
import com.petugas.pemasangan.data.CatatanSeenStore
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.CatatanShared
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.16-m17 — pemantau CATATAN PUSAT atas data yang sudah terkirim.
 *
 * Admin pusat menulis catatan pada laporan (saat mengubah status
 * TERTUNDA <-> SELESAI atau lewat tombol catatan); catatan itu sampai ke
 * sini lewat GET /api/reports/catatan-pusat (identitas header perangkat,
 * pola /api/chat/summary). Pemanggilan dijadwalkan OLEH [ChatPoller]
 * (loop 15 dtk + WorkManager 15 mnt) sehingga tidak ada mesin jadwal
 * kedua yang perlu diurus.
 *
 * Per laporan (kunci client_ref "L<id>-<createdAt>"):
 *  - BELUM DILIHAT  -> dihitung lencana angka (CatatanShared.unread).
 *  - BELUM DIBERI TAHU -> notifikasi status bar (tanpa pengulangan).
 * Catatan juga DISALINKAN ke data lokal Room (kolom catatanPusat) agar
 * tetap tampil di Detail walau aplikasi dibuka tanpa jaringan.
 */
object CatatanPoller {

    private const val CHANNEL_CATATAN = "catatan_pusat"

    private val kunci = Mutex()

    suspend fun checkOnce(app: Context) {
        kunci.withLock { periksa(app) }
    }

    private suspend fun periksa(app: Context) {
        val settings = SettingsRepository(app)
        if (!settings.isRegistered()) return

        // v1.18-m20 — migrasi sekali: penanda lama (kode 28/29 yang
        // menelan catatan pertama secara diam) direset agar catatan
        // yang sudah ada dievaluasi ulang dgn aturan baru.
        CatatanSeenStore.migrasiResetSekali(app)

        val hasil = PlnApi.fetchCatatanPusat(
            settings.deviceId(),
            settings.androidId(),
        ).getOrNull() ?: return

        // State bersama untuk banner menu & titik daftar data.
        CatatanShared.notes.clear()
        CatatanShared.notes.addAll(hasil.catatan)

        // Salin catatan ke data lokal yang cocok (Room) — tampil di Detail.
        salinkanKeLokal(app, hasil.catatan)

        for (item in hasil.catatan) {
            val ref = item.clientRef
            if (ref.isBlank()) continue

            if (!CatatanSeenStore.has(app, ref)) {
                // Pertama kali pemantau melihat catatan utk laporan ini.
                // v1.18-m20 (keluhan pemilik): dulu catatan PERTAMA ditelan
                // diam-diam -> tidak ada notifikasi status bar & tidak ada
                // tanda merah. Kini catatan SEGAR (≤48 jam) langsung diberi
                // notifikasi dan tetap dihitung BELUM DILIHAT (tanda merah
                // menyala sampai petugas membukanya). Catatan sejarah yang
                // sudah lama tetap ditandai diam agar pembaruan/pasang
                // ulang tidak membanjiri status bar.
                val segar = segar48Jam(item.catatanAt)
                val sedangDibuka = CatatanShared.activeClientRef == ref
                if (segar && !sedangDibuka) {
                    munculkanNotifikasi(app, item)
                    CatatanSeenStore.setNotified(app, ref, item.catatanAt)
                    // seen SENGAJA tidak diisi -> tanda merah tetap menyala
                    // sampai petugas benar-benar membuka datanya.
                } else {
                    CatatanSeenStore.set(app, ref, item.catatanAt)
                    CatatanSeenStore.setNotified(app, ref, item.catatanAt)
                }
                continue
            }

            // Notifikasi hanya untuk catatan yang lebih baru dari yang
            // terakhir diberi tahu, dan tidak sedang terbuka di layar.
            val sudahDiberiTahu = CatatanSeenStore.notified(app, ref)
            val sedangDibuka = CatatanShared.activeClientRef == ref
            if (item.catatanAt > sudahDiberiTahu && !sedangDibuka) {
                munculkanNotifikasi(app, item)
                CatatanSeenStore.setNotified(app, ref, item.catatanAt)
            }
        }

        hitungUlang(app)
    }

    /** Jumlah catatan yang belum dilihat — angka merah di menu/daftar. */
    fun hitungUlang(app: Context) {
        CatatanShared.unread = CatatanShared.notes.count { item ->
            item.clientRef.isNotBlank() && item.catatanAt > CatatanSeenStore.get(app, item.clientRef)
        }
    }

    /** Tandai catatan suatu data sudah dilihat (dipanggil layar Detail). */
    fun tandaiDilihat(app: Context, clientRef: String) {
        if (clientRef.isBlank()) return
        val item = CatatanShared.notes.firstOrNull { it.clientRef == clientRef } ?: return
        CatatanSeenStore.set(app, clientRef, item.catatanAt)
        CatatanSeenStore.setNotified(app, clientRef, item.catatanAt)
        hitungUlang(app)
    }

    /** Salin catatan server ke entitas Room yang cocok via client_ref. */
    private suspend fun salinkanKeLokal(app: Context, catatan: List<PlnApi.CatatanPusatItem>) {
        if (catatan.isEmpty()) return
        val dao = AppDatabase.get(app).installationDao()
        val semua = dao.observeAll().first()
        val byRef = semua.associateBy { clientRefOf(it) }
        for (item in catatan) {
            if (item.clientRef.isBlank()) continue
            val ent = byRef[item.clientRef] ?: continue
            val baru = item.catatanAt.takeIf { !it.isBlank() } ?: continue
            val ts = runCatching { java.time.Instant.parse(baru).toEpochMilli() }.getOrDefault(0L)
            if (ent.catatanPusat == item.catatan && ent.catatanPusatAt == ts) continue
            dao.update(
                ent.copy(catatanPusat = item.catatan, catatanPusatAt = ts),
            )
        }
    }

    /** Kunci padam lokal yang sama dengan ReportUploader saat unggah. */
    fun clientRefOf(entity: InstallationEntity): String =
        "L${entity.id}-${entity.createdAt}"

    /**
     * v1.18-m20 — catatan dianggap SEGAR bila ditulis pusat ≤48 jam
     * terakhir. Penentu apakah catatan pertama-nya layak notifikasi
     * (yang lebih tua = sejarah, cukup tanda merah tanpa notifikasi).
     */
    private fun segar48Jam(catatanAt: String): Boolean {
        val ts = runCatching { java.time.Instant.parse(catatanAt).toEpochMilli() }.getOrDefault(0L)
        if (ts <= 0L) return false
        return System.currentTimeMillis() - ts <= 48 * 60 * 60 * 1000L
    }

    private suspend fun munculkanNotifikasi(context: Context, item: PlnApi.CatatanPusatItem) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        buatChannel(context)

        val judul = "Catatan pusat — ${item.customerName.ifBlank { "Data terkirim" }}"
        val isi = item.catatan

        val notif = NotificationCompat.Builder(context, CHANNEL_CATATAN)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(judul)
            .setContentText(isi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(isi))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(intentBuka(context, item))
            .build()

        nm.notify(("catatan:" + item.reportId).hashCode(), notif)
    }

    /** Ketukan notifikasi membuka Detail data terkait bila ada di ponsel. */
    private suspend fun intentBuka(context: Context, item: PlnApi.CatatanPusatItem): PendingIntent {
        var route = "list"
        if (item.clientRef.isNotBlank()) {
            val dao = AppDatabase.get(context).installationDao()
            val semua = dao.observeAll().first()
            val ent = semua.firstOrNull { clientRefOf(it) == item.clientRef }
            if (ent != null) route = "detail/${ent.id}"
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
            ("catatan:" + item.reportId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Channel catatan pusat — pentingnya TINGGI agar muncul heads-up. */
    private fun buatChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CATATAN,
                "Catatan Pusat",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifikasi instruksi/keterangan pusat atas data yang sudah dikirim"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
            }
        )
    }
}
