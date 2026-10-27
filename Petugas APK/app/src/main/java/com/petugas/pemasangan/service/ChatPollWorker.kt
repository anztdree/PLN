package com.petugas.pemasangan.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * M8 — pekerjaan berkala WorkManager: pemeriksa pesan baru saat proses
 * aplikasi sudah ditutup ponsel (fallback dari [ChatPoller]).
 *
 * Tanpa notifikasi penanda, tanpa layanan latar — sekali jalan tiap
 * ±15 menit: tarik ringkasan pesan, bila ada chat baru dari orang lain
 * munculkan notifikasi status bar. Jadwal bertahan setelah ponsel
 * dinyalakan ulang (tidak perlu BootReceiver lagi).
 */
class ChatPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            ChatPoller.checkOnce(applicationContext)
            Result.success()
        } catch (_: Exception) {
            // Jaringan/server bermasalah — WorkManager mencoba lagi nanti.
            Result.retry()
        }
    }
}
