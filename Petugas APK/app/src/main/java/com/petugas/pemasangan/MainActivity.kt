package com.petugas.pemasangan

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                        AppNavHost()
                    }
                }
            }
        }
        setupHeartbeat()
    }

    /**
     * M4 — tandai kehadiran ke pusat selama aplikasi TERBUKA saja (Opsi A):
     * heartbeat langsung saat layar tampil, lalu tiap 3 menit selama aplikasi
     * masih di depan. Saat aplikasi ditutup/minimize, loop berhenti otomatis
     * sehingga status online di pusat kendali berarti "petugas sedang membuka
     * aplikasi" — hemat baterai, tanpa layanan latar.
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
                    delay(3 * 60_000L)
                }
            }
        }
    }
}
