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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.ui.AppNavHost
import com.petugas.pemasangan.ui.SplashScreen
import com.petugas.pemasangan.ui.theme.PetugasTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settings = remember { SettingsRepository(context.applicationContext) }
            val darkTheme by settings.themeFlow.collectAsStateWithLifecycle(initialValue = false)

            PetugasTheme(darkTheme = darkTheme) {
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
    }
}
