package com.petugas.pemasangan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.AppNavHost
import com.petugas.pemasangan.ui.SplashScreen
import com.petugas.pemasangan.ui.theme.PetugasTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Rute dari ketukan notifikasi yang menunggu diproses AppNavHost. */
    private var openRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            // MODE GELAP PATEN: identitas aplikasi selalu navy malam + emas listrik.
            PetugasTheme {
                var showSplash by remember { mutableStateOf(true) }
                Crossfade(
                    targetState = showSplash,
                    animationSpec = tween(durationMillis = 450),
                    label = "splashCrossfade"
                ) { splash ->
                    if (splash) {
                        SplashScreen(onDone = { showSplash = false })
                    } else {
                        AppNavHost(openRoute = openRoute)
                    }
                }
            }
        }
        setupHeartbeat()
        mintaIzinNotifikasi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Aplikasi sudah terbuka, notifikasi diketuk -> langsung navigasi.
        intent.getStringExtra(EXTRA_ROUTE)?.let { openRoute = it }
    }

    /**
     * M4 — tandai kehadiran ke pusat selama aplikasi TERBUKA saja:
     * heartbeat langsung saat layar tampil, lalu tiap 1 menit selama
     * aplikasi masih di depan. Saat aplikasi ditutup/minimize, loop
     * berhenti otomatis sehingga status online di pusat kendali berarti
     * "petugas sedang membuka aplikasi" — dan karena detak kini rapat,
     * status ONLINE/OFFLINE terpantau akurat dalam ±2,5 menit.
     */
    private fun setupHeartbeat() {
        val settings = SettingsRepository(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    try {
                        if (settings.isRegistered()) {
                            PlnApi.heartbeat(settings.deviceId(), settings.androidId())
                            // Gagal jaringan diabaikan — percobaan berikutnya menyusul.
                        }
                    } catch (_: Exception) {
                        // diamkan
                    }
                    delay(60_000L)
                }
            }
        }
    }

    /** Android 13+ butuh izin notifikasi secara eksplisit (sekali saja). */
    private fun mintaIzinNotifikasi() {
        if (Build.VERSION.SDK_INT < 33) return
        val sudah = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!sudah) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }
    }

    companion object {
        const val EXTRA_ROUTE = "extra_route"
        private const val REQ_NOTIF = 71
    }
}
