package com.petugas.pemasangan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = PlnYellow,
    onPrimary = Color(0xFF1C1400),
    primaryContainer = Color(0xFF3A2E00),
    onPrimaryContainer = Color(0xFFFFEFA6),
    secondary = VoltCyan,
    onSecondary = Color(0xFF00212B),
    secondaryContainer = Color(0xFF004C60),
    onSecondaryContainer = Color(0xFFB8F0FF),
    tertiary = PlnAmber,
    error = PlnRed,
    background = DkVoid,
    onBackground = DkTextHi,
    surface = DkBg,
    onSurface = DkTextHi,
    surfaceVariant = DkPanel,
    onSurfaceVariant = DkTextMid,
    outline = DkOutline,
    surfaceContainer = Color(0xFF101830),
    surfaceContainerHigh = Color(0xFF141D38)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFF3B71B),
    onPrimary = Color(0xFF1C1400),
    primaryContainer = Color(0xFFFFE9A8),
    onPrimaryContainer = Color(0xFF4A3600),
    secondary = Color(0xFF0FA8DC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF1FF),
    onSecondaryContainer = Color(0xFF06465C),
    tertiary = PlnAmber,
    error = Color(0xFFE63946),
    background = LtBg,
    onBackground = LtTextHi,
    surface = Color(0xFFFFFDF4),
    onSurface = LtTextHi,
    surfaceVariant = Color(0xFFF3EDD9),
    onSurfaceVariant = LtTextMid,
    outline = LtOutline
)

/**
 * Tema v3.0 "Electric Premium".
 * Palet kustom tersedia via LocalElectric (warna glow, gradasi, kaca).
 */
@Composable
fun PetugasTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalElectric provides if (darkTheme) DarkElectric else LightElectric
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography,
            content = content
        )
    }
}
