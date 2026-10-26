package com.petugas.pemasangan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Green600,
    onPrimary = Color.White,
    primaryContainer = Green100,
    onPrimaryContainer = Green900,
    secondary = Teal600,
    onSecondary = Color.White,
    secondaryContainer = Teal100,
    onSecondaryContainer = Teal900,
    error = Red600,
    background = SurfaceGray,
    surface = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Emerald300,
    onPrimary = Emerald50,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = Teal300,
    onSecondary = Color(0xFF00332E),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Teal100,
    error = Color(0xFFF87171),
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

/**
 * Tema aplikasi v2.0: mendukung mode terang & gelap.
 * Pilihan tema tersimpan di DataStore dan diwariskan dari MainActivity.
 */
@Composable
fun PetugasTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
