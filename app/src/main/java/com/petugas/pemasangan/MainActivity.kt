package com.petugas.pemasangan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.petugas.pemasangan.ui.AppNavHost
import com.petugas.pemasangan.ui.theme.PetugasTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PetugasTheme {
                AppNavHost()
            }
        }
    }
}
