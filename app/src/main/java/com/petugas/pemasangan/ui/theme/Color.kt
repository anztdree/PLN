package com.petugas.pemasangan.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/* =====================================================================
   v3.0 "ELECTRIC PREMIUM" — identitas visual listrik PLN:
   kuning listrik cerah, petir merah, gelombang biru cyan (logo klasik),
   di atas kanvas navy malam yang elegan (mode gelap) / ivory hangat (terang).
   Bukan minimalis: glow, kaca, gradasi emas, percikan energi di semua sisi.
   ===================================================================== */

/* ---------- Warna inti PLN ---------- */
val PlnYellow = Color(0xFFFFDE21)   // kuning listrik khas PLN
val PlnGold = Color(0xFFFFB300)     // emas
val PlnAmber = Color(0xFFFF8F1F)    // jingga panas
val PlnRed = Color(0xFFFF4757)      // merah petir (logo PLN)
val VoltCyan = Color(0xFF3BE0FF)    // biru gelombang terang
val VoltBlue = Color(0xFF0FB4E8)    // biru gelombang (logo PLN)

/* ---------- Gradasi sangkar petir (bolt) ---------- */
val BoltGrad = listOf(
    Color(0xFFFFE95A),
    Color(0xFFFFB300),
    Color(0xFFFF6D1F)
)

/* ---------- Mode gelap: navy malam ---------- */
val DkVoid = Color(0xFF05070D)
val DkBg = Color(0xFF0B1120)
val DkPanel = Color(0xFF131C33)
val DkPanelHi = Color(0xFF1B2647)
val DkOutline = Color(0xFF2A3654)
val DkTextHi = Color(0xFFF4F6FF)
val DkTextMid = Color(0xFF9AA7C7)

/* ---------- Mode terang: ivory hangat ---------- */
val LtBg = Color(0xFFF8F5EA)
val LtOutline = Color(0xFFE3D7B4)
val LtTextHi = Color(0xFF121826)
val LtTextMid = Color(0xFF5C6B8A)

/**
 * Token warna "elektrik" yang dipakai komponen kustom
 * (latar beranimasi, panel kaca, ikon Canvas, glow) —
 * tersedia di dalam PetugasTheme lewat [LocalElectric].
 */
@Immutable
data class ElectricColors(
    val dark: Boolean,
    val boltGrad: List<Color>,
    val yellow: Color,
    val gold: Color,
    val amber: Color,
    val cyan: Color,
    val red: Color,
    val bg0: Color,
    val bg1: Color,
    val glass: Color,
    val glassBorderA: Color,
    val glassBorderB: Color,
    val textHi: Color,
    val textMid: Color,
    val glowYellow: Color,
    val glowCyan: Color
) {
    val boltBrush: Brush get() = Brush.linearGradient(boltGrad)
    val goldBrush: Brush get() = Brush.linearGradient(listOf(yellow, gold, amber))
    val borderBrush: Brush get() = Brush.linearGradient(listOf(glassBorderA, glassBorderB))
}

val DarkElectric = ElectricColors(
    dark = true,
    boltGrad = BoltGrad,
    yellow = PlnYellow,
    gold = PlnGold,
    amber = PlnAmber,
    cyan = VoltCyan,
    red = PlnRed,
    bg0 = DkVoid,
    bg1 = DkBg,
    glass = Color(0xCC101830),
    glassBorderA = Color(0x66FFD84D),
    glassBorderB = Color(0x403BE0FF),
    textHi = DkTextHi,
    textMid = DkTextMid,
    glowYellow = Color(0xFFFFD84D),
    glowCyan = VoltCyan
)

val LightElectric = ElectricColors(
    dark = false,
    boltGrad = BoltGrad,
    yellow = PlnYellow,
    gold = PlnGold,
    amber = PlnAmber,
    cyan = Color(0xFF0FA8DC),
    red = Color(0xFFE63946),
    bg0 = LtBg,
    bg1 = Color(0xFFFDFBF2),
    glass = Color(0xF2FFFFFF),
    glassBorderA = Color(0x59E8B300),
    glassBorderB = Color(0x330FA8DC),
    textHi = LtTextHi,
    textMid = LtTextMid,
    glowYellow = Color(0xFFEDBB1F),
    glowCyan = Color(0xFF18A8D8)
)

val LocalElectric = staticCompositionLocalOf { DarkElectric }
