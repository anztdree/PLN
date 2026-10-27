package com.petugas.pemasangan.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember { SettingsRepository(appContext) }
    val e = LocalElectric.current

    var officerName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings.officerNameFlow.collect {
            if (!loaded) {
                officerName = it
                loaded = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

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

            // Profil petugas — nama terdaftar di pusat, permanen (baca-saja)
            GlassPanel(modifier = Modifier.entrance(60)) {
                Section(PlnIconKind.USER, "PROFIL PETUGAS")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(e.goldBrush),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            officerName.trim().take(1).uppercase().ifBlank { "P" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF241A00)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            officerName.ifBlank { "—" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = e.textHi,
                            maxLines = 1
                        )
                        Text(
                            "Nama terdaftar di pusat kendali & tidak dapat diubah",
                            style = MaterialTheme.typography.labelSmall,
                            color = e.textMid
                        )
                    }
                }
            }

            // Notifikasi & baterai — kunci notifikasi pesan masuk (M7)
            GlassPanel(modifier = Modifier.entrance(100)) {
                Section(PlnIconKind.CHAT, "NOTIFIKASI & BATERAI")

                var notifAktif by remember { mutableStateOf(false) }
                var bateraiBebas by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    while (true) {
                        notifAktif = nm.areNotificationsEnabled()
                        bateraiBebas = pm.isIgnoringBatteryOptimizations(appContext.packageName)
                        delay(3000)
                    }
                }

                // --- baris 1: izin notifikasi ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (notifAktif) Color(0x2458E38A) else e.red.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlnIcon(
                            PlnIconKind.CHECK,
                            size = 18.dp,
                            tint = if (notifAktif) Color(0xFF58E38A) else e.red
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Notifikasi pesan",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = e.textHi
                        )
                        Text(
                            if (notifAktif) "Aktif — pesan baru akan muncul"
                            else "MATI — pesan baru tidak terlihat",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (notifAktif) Color(0xFF58E38A) else e.red
                        )
                    }
                    if (!notifAktif) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("Buka setelan", color = e.yellow) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // --- baris 2: pengecualian baterai (anti pemeriksa pesan ditunda OEM) ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(e.yellow.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlnIcon(PlnIconKind.BOLT, size = 18.dp, tint = e.yellow)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Berjalan di latar tanpa hambatan",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = e.textHi
                        )
                        Text(
                            if (bateraiBebas) "Sudah diizinkan — pesan baru tetap terpantau saat aplikasi ditutup"
                            else "Izinkan agar notifikasi tidak terhenti hemat baterai",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bateraiBebas) Color(0xFF58E38A) else e.textMid
                        )
                    }
                    if (!bateraiBebas) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${appContext.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                Toast.makeText(appContext, "Setelan tidak tersedia di ponsel ini", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Izinkan", color = e.yellow) }
                    }
                }
            }

            // Tentang
            GlassPanel(modifier = Modifier.entrance(140)) {
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
                            "v1.0.0 • PLN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = e.yellow
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AboutLine("Aplikasi ini dibuat untuk mempermudah petugas saat mencatat pemasangan baru di lapangan — foto titik sambung, data pelanggan, sampai titik lokasinya jadi satu tempat.")
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
