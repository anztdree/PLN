package com.petugas.pemasangan

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Application class: konfigurasi osmdroid (user agent & lokasi cache tile)
 * dipasang sebelum Activity apa pun dibuat.
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
    }
}
