package com.petugas.pemasangan.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.chat.ChatShared
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GradientHeadline
import com.petugas.pemasangan.ui.fx.IconTile
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlinx.coroutines.delay

/**
 * Daftar obrolan: Obrolan Umum (semua tim), Pusat (pesan pribadi ke admin),
 * dan pesan pribadi ke sesama petugas. Daftar petugas diambil langsung
 * dari server sehingga petugas baru otomatis muncul — dan kini ikut
 * menyala TERUS (tiap 15 dtk) selama halaman terbuka, sehingga anggota
 * baru & status online/offline terlihat tanpa menutup halaman.
 */
@Composable
fun ChatListScreen(
    myId: Long,
    myName: String,
    onBack: () -> Unit,
    onOpenGlobal: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenPetugas: (id: Long, nama: String) -> Unit
) {
    val e = LocalElectric.current
    val appContext = LocalContext.current.applicationContext

    // Cache dulu (instan), baru segarkan dari server.
    var others by remember { mutableStateOf(ChatShared.petugas?.filter { it.id != myId }) }
    var onlineSemua by remember { mutableStateOf(ChatShared.petugas?.count { it.online } ?: 0) }
    var failed by remember { mutableStateOf(false) }

    suspend fun load() {
        PlnApi.fetchPetugas()
            .onSuccess { list ->
                ChatShared.petugas = list
                onlineSemua = list.count { it.online }
                others = list.filter { it.id != myId }.sortedBy { it.nama.lowercase() }
                failed = false
            }
            .onFailure { if (others == null) failed = true }
    }

    LaunchedEffect(Unit) {
        // Segarkan lencana belum-terbaca sejak halaman dibuka.
        val s = SettingsRepository(appContext)
        PlnApi.fetchSummary(s.deviceId(), s.androidId()).onSuccess {
            ChatShared.unreadTotal = it.unreadTotal
            ChatShared.summary = it
        }
        load()
        while (true) {
            delay(15_000L)
            load()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // ================= HEADER =================
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
                Column(modifier = Modifier.weight(1f)) {
                    GradientHeadline(
                        text = "OBROLAN",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                    )
                    Text(
                        "Diskusi tim — pesan teks",
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textMid
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(e.goldBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        myName.trim().take(1).uppercase().ifBlank { "P" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF241A00)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (others == null && !failed) {
                // Muat: panel kerangka sederhana
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) {
                        GlassPanel(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(e.textMid.copy(alpha = 0.15f))
                                )
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(e.textMid.copy(alpha = 0.15f))
                                )
                            }
                        }
                    }
                }
            } else if (failed) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PlnIcon(PlnIconKind.METER, size = 28.dp, tint = e.textMid)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Daftar petugas belum bisa dimuat",
                            style = MaterialTheme.typography.bodySmall,
                            color = e.textMid
                        )
                        Text(
                            "Periksa internet, lalu buka halaman ini lagi.",
                            style = MaterialTheme.typography.labelSmall,
                            color = e.textMid.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ---------- PINNED: OBROLAN UMUM ----------
                    item {
                        ChatEntryCard(
                            modifier = Modifier.entrance(60),
                            title = "Obrolan Umum",
                            subtitle = if (others != null) "Semua petugas & pusat · $onlineSemua online"
                                       else "Semua petugas & pusat",
                            iconKind = PlnIconKind.CHAT,
                            tileGradient = e.goldBrush,
                            badge = ChatShared.unreadOf("global"),
                            onClick = onOpenGlobal
                        )
                    }

                    // ---------- PUSAT (ADMIN) ----------
                    item {
                        ChatEntryCard(
                            modifier = Modifier.entrance(120),
                            title = "Pusat",
                            subtitle = "Admin — pesan pribadi",
                            iconKind = PlnIconKind.BOLT,
                            tileGradient = Brush.linearGradient(
                                listOf(Color(0xFF52E5FF), Color(0xFF0E9BC8))
                            ),
                            badge = ChatShared.unreadOf("admin"),
                            onClick = onOpenAdmin
                        )
                    }

                    // ---------- SESEMUA PETUGAS ----------
                    item {
                        Text(
                            "PETUGAS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = e.textMid,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    }

                    val list = others.orEmpty()
                    if (list.isEmpty()) {
                        item {
                            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (others == null) "Memuat daftar petugas…"
                                    else "Belum ada petugas lain yang terdaftar.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = e.textMid,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    } else {
                        items(list, key = { it.id }) { p ->
                            GlassPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenPetugas(p.id, p.nama) }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(e.glass),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            p.nama.trim().take(1).uppercase().ifBlank { "?" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = e.yellow
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            p.nama,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = e.textHi,
                                            maxLines = 1
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (p.online) Color(0xFF58E38A)
                                                        else e.textMid.copy(alpha = 0.45f)
                                                    )
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                if (p.online) "Online" else "Offline",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (p.online) Color(0xFF58E38A) else e.textMid
                                            )
                                        }
                                    }
                                    // Lencana pesan pribadi yang belum dibaca.
                                    val badgeDm = ChatShared.unreadOf(p.id.toString())
                                    if (badgeDm > 0) {
                                        Box(
                                            modifier = Modifier
                                                .background(e.red, RoundedCornerShape(50))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                if (badgeDm > 99) "99+" else badgeDm.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    PlnIcon(PlnIconKind.CHEVRON, size = 14.dp, tint = e.textMid)
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ChatEntryCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    iconKind: PlnIconKind,
    tileGradient: Brush,
    badge: Int = 0,
    onClick: () -> Unit
) {
    val e = LocalElectric.current
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                iconKind,
                sizeDp = 42.dp,
                gradient = tileGradient,
                iconTint = Color(0xFF241A00),
                iconScale = 0.62f
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = e.textHi,
                    maxLines = 1
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textMid,
                    maxLines = 1
                )
            }
            // Lencana angka pesan belum terbaca percakapan ini.
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .background(e.red, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            PlnIcon(PlnIconKind.CHEVRON, size = 14.dp, tint = e.textMid)
        }
    }
}
