package com.petugas.pemasangan.ui.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GradientHeadline
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val POLL_MS = 10_000L
private const val MAX_CHARS = 2000

/**
 * Satu ruang percakapan (Obrolan Umum / pesan pribadi). Pesan teks saja.
 * Pesan baru diambil otomatis tiap 10 detik selama layar ini terbuka
 * (Opsi A — polling; tanpa layanan latar agar baterai hemat).
 */
@Composable
fun ChatScreen(
    title: String,
    scope: String,               // "global" | "private"
    targetId: String?,           // utk private: "admin" atau id petugas lawan
    myId: Long,
    showNames: Boolean,          // global: tampilkan nama pengirim di atas gelembung
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember { SettingsRepository(appContext) }
    val scopeCd = rememberCoroutineScope()
    val e = LocalElectric.current

    var msgs by remember { mutableStateOf<List<PlnApi.ChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var deviceId by remember { mutableStateOf("") }
    var androidId by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val fmtClock = remember { SimpleDateFormat("HH:mm", Locale("id", "ID")) }
    val fmtDay = remember { SimpleDateFormat("d MMMM yyyy", Locale("id", "ID")) }

    LaunchedEffect(scope, targetId) {
        deviceId = settings.deviceId()
        androidId = settings.androidId()
        var lastId = 0L

        suspend fun muat(after: Long) {
            PlnApi.fetchMessages(
                scope = scope,
                a = myId.toString(),
                b = targetId ?: "",
                after = after,
                myDeviceId = deviceId,
                myAndroidId = androidId
            ).onSuccess { baru ->
                if (after == 0L) {
                    msgs = baru
                } else {
                    val known = msgs.map { it.id }.toHashSet()
                    val tambah = baru.filter { it.id !in known }
                    if (tambah.isNotEmpty()) msgs = msgs + tambah
                }
                lastId = msgs.maxOfOrNull { it.id } ?: 0L
            }
        }

        // Muat awal: pesan terakhir
        loading = true
        muat(0L)
        loading = false

        // Polling selama layar terbuka
        while (true) {
            delay(POLL_MS)
            muat(lastId)
        }
    }

    // Selalu tampilkan pesan paling baru
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) {
            runCatching { listState.scrollToItem(msgs.size - 1) }
        }
    }

    fun kirim() {
        val teks = draft.trim()
        if (teks.isEmpty() || sending) return
        sending = true
        draft = ""
        scopeCd.launch {
            PlnApi.sendMessage(
                scope = scope,
                targetId = targetId,
                body = teks,
                myDeviceId = deviceId,
                myAndroidId = androidId
            ).onSuccess { m ->
                if (msgs.none { it.id == m.id }) msgs = msgs + m
                sending = false
            }.onFailure {
                sending = false
                draft = teks // kembalikan tulisan agar tinggal tekan kirim lagi
                Toast.makeText(context, "Pesan gagal terkirim — periksa internet lalu coba lagi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ================= HEADER =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(onClick = onBack) {
                    PlnIcon(PlnIconKind.BACK, tint = e.textHi, size = 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    GradientHeadline(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        if (scope == "global") "Semua petugas & pusat" else "Pesan pribadi",
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textMid
                    )
                }
                PlnIcon(PlnIconKind.CHAT, size = 22.dp, tint = e.yellow)
            }

            // ================= PESAN =================
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (loading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Memuat pesan…",
                                color = e.textMid,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (msgs.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 46.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PlnIcon(PlnIconKind.CHAT, size = 34.dp, tint = e.textMid)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Belum ada pesan di sini",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = e.textMid
                            )
                            Text(
                                "Mulai percakapan pertama dengan menulis pesan di bawah.",
                                style = MaterialTheme.typography.bodySmall,
                                color = e.textMid.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    itemsIndexed(msgs, key = { _, m -> m.id }) { idx, m ->
                        val prev = if (idx > 0) msgs[idx - 1] else null
                        Column {
                            if (prev == null || dayKeyOf(prev.createdAt) != dayKeyOf(m.createdAt)) {
                                Text(
                                    fmtDay.format(parseIso(m.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = e.textMid,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                            Bubble(
                                m = m,
                                own = m.senderType == "petugas" && m.senderId == myId.toString(),
                                showName = showNames && !(m.senderType == "petugas" && m.senderId == myId.toString()),
                                clock = fmtClock.format(parseIso(m.createdAt))
                            )
                        }
                    }
                }
            }

            // ================= KETIK PESAN =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_CHARS) },
                    placeholder = { Text("Tulis pesan…", color = e.textMid.copy(alpha = 0.7f)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = e.yellow.copy(alpha = 0.7f),
                        unfocusedBorderColor = e.glassBorderB,
                        cursorColor = e.yellow,
                        focusedTextColor = e.textHi,
                        unfocusedTextColor = e.textHi,
                        focusedContainerColor = e.glass,
                        unfocusedContainerColor = e.glass,
                        focusedPlaceholderColor = e.textMid.copy(alpha = 0.7f),
                        unfocusedPlaceholderColor = e.textMid.copy(alpha = 0.7f)
                    )
                )
                Spacer(Modifier.width(8.dp))
                val bisaKirim = draft.isNotBlank() && !sending
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (bisaKirim) e.goldBrush
                            else Brush.linearGradient(
                                listOf(e.textMid.copy(alpha = 0.25f), e.textMid.copy(alpha = 0.18f))
                            )
                        )
                        .clickable(enabled = bisaKirim) { kirim() },
                    contentAlignment = Alignment.Center
                ) {
                    PlnIcon(
                        PlnIconKind.SEND,
                        size = 22.dp,
                        tint = if (bisaKirim) Color(0xFF241A00) else e.textMid
                    )
                }
            }
        }
    }
}

private fun dayKeyOf(iso: String): String = iso.take(10)

private fun parseIso(iso: String): Date =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.take(19)) ?: Date(0)
    }.getOrDefault(Date(0))

@Composable
private fun Bubble(m: PlnApi.ChatMessage, own: Boolean, showName: Boolean, clock: String) {
    val e = LocalElectric.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            if (showName) {
                Text(
                    m.senderNama,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = e.yellow,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                )
            }
            Column(
                modifier = Modifier
                    .then(
                        if (own) {
                            Modifier.background(e.goldBrush, RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                        } else {
                            Modifier.background(e.glass, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    m.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (own) Color(0xFF241A00) else e.textHi
                )
                Text(
                    clock,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (own) Color(0xFF241A00).copy(alpha = 0.55f) else e.textMid,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
