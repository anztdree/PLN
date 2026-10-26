package com.petugas.pemasangan.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.fx.BoltMark
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GoldButton
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember { SettingsRepository(appContext) }
    val scope = rememberCoroutineScope()
    val e = LocalElectric.current

    var officerName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var isDark by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        settings.officerNameFlow.collect {
            if (!loaded) {
                officerName = it
                loaded = true
            }
        }
    }
    LaunchedEffect(Unit) {
        settings.themeFlow.collect { isDark = it }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(onClick = onBack) {
                    PlnIcon(PlnIconKind.BACK, tint = e.textHi, size = 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Pengaturan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = e.textHi
                    )
                    Text(
                        "Preferensi aplikasi petugas",
                        style = MaterialTheme.typography.labelSmall,
                        color = e.textMid
                    )
                }
            }

            // Profil petugas
            GlassPanel(modifier = Modifier.entrance(60)) {
                Section(PlnIconKind.USER, "PROFIL PETUGAS")
                OutlinedTextField(
                    value = officerName,
                    onValueChange = { officerName = it },
                    label = { Text("Nama Petugas") },
                    placeholder = { Text("Contoh: Budi Santoso", color = e.textMid.copy(alpha = 0.6f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = e.yellow,
                        unfocusedBorderColor = e.glassBorderB,
                        cursorColor = e.yellow,
                        focusedLabelColor = e.yellow,
                        unfocusedLabelColor = e.textMid,
                        focusedTextColor = e.textHi,
                        unfocusedTextColor = e.textHi
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                GoldButton(
                    text = "Simpan Nama",
                    onClick = {
                        scope.launch {
                            settings.setOfficerName(officerName.trim())
                            Toast.makeText(context, "Nama petugas disimpan", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 48.dp,
                    leading = { PlnIcon(PlnIconKind.CHECK, size = 17.dp, tint = Color(0xFF241A00)) }
                )
            }

            // Tampilan
            GlassPanel(modifier = Modifier.entrance(140)) {
                Section(PlnIconKind.METER, "TAMPILAN")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(e.yellow.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlnIcon(PlnIconKind.BOLT, size = 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Mode Gelap Electric",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = e.textHi
                        )
                        Text(
                            "Navy malam + glow kuning listrik",
                            style = MaterialTheme.typography.bodySmall,
                            color = e.textMid
                        )
                    }
                    Switch(
                        checked = isDark,
                        onCheckedChange = { dark ->
                            scope.launch { settings.setTheme(dark) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = e.gold,
                            checkedThumbColor = e.yellow,
                            checkedBorderColor = e.gold,
                            uncheckedTrackColor = e.glass,
                            uncheckedThumbColor = e.textMid
                        )
                    )
                }
            }

            // Tentang
            GlassPanel(modifier = Modifier.entrance(220)) {
                Section(PlnIconKind.BOLT, "TENTANG APLIKASI")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoltMark(size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Petugas PLN",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = e.textHi
                        )
                        Text(
                            "v3.0 • ELECTRIC PREMIUM",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = e.yellow
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AboutLine("Pencatatan data pemasangan listrik: foto, koordinat GPS, peta, dan riwayat.")
                AboutLine("Data tersimpan lokal di perangkat (database Room) — aman tanpa internet.")
                AboutLine("Peta © OpenStreetMap (osmdroid), tanpa API key.")
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(kind: PlnIconKind, label: String) {
    val e = LocalElectric.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(e.yellow.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            PlnIcon(kind, size = 16.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.6.sp,
            color = e.textHi
        )
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun AboutLine(text: String) {
    val e = LocalElectric.current
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("•  ", color = e.yellow, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodySmall, color = e.textMid)
    }
}
