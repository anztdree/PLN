package com.petugas.pemasangan

import android.app.Application
import com.petugas.pemasangan.service.ChatPoller
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Application class: konfigurasi osmdroid (user agent & lokasi cache tile)
 * dipasang sebelum Activity apa pun dibuat.
 *
 * M8 — pemantau pesan baru (ChatPoller) dinyalakan di sini agar notifikasi
 * chat baru siap sejak proses aplikasi hidup — TANPA notifikasi permanen
 * di status bar.
 */
class PetugasApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val basePath = File(getExternalFilesDir(null), "osmdroid").apply { mkdirs() }
        val tileCache = File(cacheDir, "osmdroid-tiles").apply { mkdirs() }

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = basePath
            osmdroidTileCache = tileCache
        }

        ChatPoller.start(this)
    }
}
