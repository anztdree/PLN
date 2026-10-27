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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.ConvSeenStore
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

/** Pesan dari pengirim yang sama dalam jangka ini dianggap satu grup. */
private const val GROUP_GAP_MS = 5 * 60_000L

/** Warna khusus status gagal kirim (merah lembut yang nyaman di tema gelap). */
private val GagalMerah = Color(0xFFFF8A80)

/**
 * Pesan yang belum dikonfirmasi server: sedang mengirim (pending) atau
 * gagal terkirim (bisa diketuk untuk mencoba lagi). Memakai localId negatif
 * agar tidak bertabrakan dengan id pesan dari server.
 */
private data class PendingMsg(
    val localId: Long,
    val text: String,
    val failed: Boolean = false,
)

/** Satu baris daftar percakapan: pesan server ATAU pesan pending milik sendiri. */
private data class ChatRow(
    val key: Long,
    val msg: PlnApi.ChatMessage?,
    val pending: PendingMsg?,
    val groupStart: Boolean,   // pesan pertama dari rangkaian pengirim yang sama
    val groupEnd: Boolean,     // pesan terakhir dari rangkaian
    val dayChanged: Boolean,   // tampilkan chip tanggal di atas baris ini
)

/**
 * Satu ruang percakapan (Obrolan Umum / pesan pribadi). Pesan teks saja.
 *
 * Kecepatan: isi terakhir diingat (cache memori) sehingga membuka kembali
 * obrolan TAMPIL LANGSUNG, lalu pembaruan diambil di belakang tiap 10 detik.
 *
 * Gelembung efisien: pesan beruntun dari pengirim sama digabung menjadi
 * satu rangkaian rapat — nama hanya di pesan pertama, jam hanya di pesan
 * terakhir, jarak antarpesan dikecilkan.
 *
 * Status pesan milik sendiri:
 *  - "Mengirim…" (jam)      : belum dikonfirmasi server (pending)
 *  - "Terkirim" (centang)   : sudah sampai di server
 *  - "Gagal — ketuk…"       : gagal, ketuk gelembung untuk kirim ulang
 *  - "Dilihat oleh: …"      : siapa saja yang sudah membaca (receipts)
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

    val convKey = remember(scope, targetId, myId) {
        ChatShared.convKeyOf(scope, myId, targetId)
    }

    // Percakapan ini sedang terbuka -> layanan notifikasi melewatkannya.
    DisposableEffect(convKey) {
        ChatShared.activeConvKey = convKey
        onDispose {
            if (ChatShared.activeConvKey == convKey) ChatShared.activeConvKey = null
        }
    }

    // Cache: layar langsung terisi tanpa menunggu jaringan.
    val cached = remember(convKey) { ChatShared.messages(convKey) }
    var msgs by remember { mutableStateOf(cached ?: emptyList()) }
    var pending by remember { mutableStateOf<List<PendingMsg>>(emptyList()) }
    var viewers by remember { mutableStateOf<List<PlnApi.Viewer>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(cached == null) }
    var deviceId by remember { mutableStateOf("") }
    var androidId by remember { mutableStateOf("") }

    // Daftar petugas (untuk status online di header) — dari cache dulu.
    var petugasList by remember { mutableStateOf(ChatShared.petugas) }

    val listState = rememberLazyListState()
    val fmtClock = remember { SimpleDateFormat("HH:mm", Locale("id", "ID")) }
    val fmtDay = remember { SimpleDateFormat("d MMMM yyyy", Locale("id", "ID")) }
    val todayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val myIdStr = myId.toString()
    fun isOwn(m: PlnApi.ChatMessage) = m.senderType == "petugas" && m.senderId == myIdStr

    LaunchedEffect(scope, targetId, convKey) {
        deviceId = settings.deviceId()
        androidId = settings.androidId()
        var lastId = msgs.maxOfOrNull { it.id } ?: 0L
        var cycle = 0

        suspend fun muatAwal() {
            PlnApi.fetchMessages(
                scope = scope,
                a = myId.toString(),
                b = targetId ?: "",
                after = 0L,
                myDeviceId = deviceId,
                myAndroidId = androidId
            ).onSuccess { hasil ->
                msgs = hasil.messages
                lastId = hasil.messages.maxOfOrNull { it.id } ?: 0L
                viewers = hasil.viewers
                ChatShared.putMessages(convKey, hasil.messages)
                ConvSeenStore.set(appContext, convKey, lastId)
            }
        }

        suspend fun muatTambahan() {
            PlnApi.fetchMessages(
                scope = scope,
                a = myId.toString(),
                b = targetId ?: "",
                after = lastId,
                myDeviceId = deviceId,
                myAndroidId = androidId
            ).onSuccess { hasil ->
                val known = msgs.map { it.id }.toHashSet()
                val tambah = hasil.messages.filter { it.id !in known }
                if (tambah.isNotEmpty()) msgs = msgs + tambah
                lastId = msgs.maxOfOrNull { it.id } ?: lastId
                viewers = hasil.viewers
                ChatShared.putMessages(convKey, msgs)
                ConvSeenStore.set(appContext, convKey, lastId)
            }
        }

        suspend fun segarkanPetugas() {
            PlnApi.fetchPetugas().onSuccess {
                ChatShared.petugas = it
                petugasList = it
            }
        }

        // Muat awal: bila cache kosong ambil penuh; bila ada cache cukup tambahan.
        if (cached == null) {
            muatAwal()
            segarkanPetugas()
            loading = false
        }

        // Polling selama layar terbuka: pesan baru tiap 10 dtk,
        // daftar petugas (status online) tiap 30 dtk.
        while (true) {
            delay(POLL_MS)
            cycle++
            muatTambahan()
            if (cycle % 3 == 0) segarkanPetugas()
        }
    }

    val rows: List<ChatRow> = remember(msgs, pending, todayKey) {
        bangunBaris(msgs, pending, ::isOwn, todayKey)
    }

    // Pesan sendiri yang paling baru = tempat status "Dilihat oleh" tampil.
    val lastOwnId: Long? = msgs.lastOrNull { isOwn(it) }?.id

    // Gulir otomatis: saat pertama terbuka, dan hanya bila pengguna
    // memang sedang berada di dekat dasar daftar (tidak membaca riwayat).
    var sudahGulirPertama by remember(convKey) { mutableStateOf(false) }
    LaunchedEffect(rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        val dekatDasar = lastVisible >= info.totalItemsCount - 2
        if (!sudahGulirPertama || dekatDasar) {
            sudahGulirPertama = true
            runCatching { listState.scrollToItem(rows.size - 1) }
        }
    }

    fun kirim(teks: String, retryLocalId: Long? = null) {
        if (teks.isEmpty() || sending) return
        sending = true
        val localId = retryLocalId ?: -(System.currentTimeMillis())
        pending = pending.filter { it.localId != localId } + PendingMsg(localId, teks)
        scopeCd.launch {
            PlnApi.sendMessage(
                scope = scope,
                targetId = targetId,
                body = teks,
                myDeviceId = deviceId,
                myAndroidId = androidId
            ).onSuccess { m ->
                pending = pending.filter { it.localId != localId }
                if (msgs.none { it.id == m.id }) msgs = msgs + m
                ConvSeenStore.set(appContext, convKey, maxOf(m.id, msgs.maxOfOrNull { it.id } ?: 0L))
                sending = false
            }.onFailure {
                sending = false
                pending = pending.map { p -> if (p.localId == localId) p.copy(failed = true) else p }
                Toast.makeText(context, "Pesan gagal terkirim — ketuk gelembungnya untuk mencoba lagi", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ================= SUBTITEL HEADER =================
    val subtitle = remember(petugasList, scope, targetId) {
        when {
            scope == "global" -> {
                val on = petugasList?.count { it.online } ?: 0
                if (petugasList == null) "Semua petugas & pusat"
                else "Semua petugas & pusat · $on online"
            }
            targetId == "admin" -> "Pusat kendali — pesan pribadi"
            else -> {
                val lawan = petugasList?.find { it.id.toString() == targetId }
                when {
                    lawan == null -> "Pesan pribadi"
                    lawan.online -> "Online"
                    else -> "Aktif ${relWaktu(lawan.lastSeenAt)}"
                }
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
                        subtitle,
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
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
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
                } else if (rows.isEmpty()) {
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
                    itemsIndexed(
                        rows,
                        key = { _, r -> r.key },
                        contentType = { _, r -> if (r.pending != null) "pending" else "msg" }
                    ) { idx, r ->
                        Column(
                            modifier = Modifier.padding(
                                top = when {
                                    r.dayChanged -> 0.dp
                                    r.groupStart -> 8.dp
                                    else -> 0.dp
                                }
                            )
                        ) {
                            if (r.dayChanged) {
                                Text(
                                    fmtDay.format(parseIso(r.msg?.createdAt ?: "")),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = e.textMid,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                            val m = r.msg
                            val p = r.pending
                            if (m != null) {
                                Bubble(
                                    m = m,
                                    own = isOwn(m),
                                    showName = showNames && !isOwn(m) && r.groupStart,
                                    showClock = r.groupEnd,
                                    clock = fmtClock.format(parseIso(m.createdAt))
                                )
                                // Status hanya di pesan sendiri yang terbaru.
                                if (isOwn(m) && r.groupEnd && m.id == lastOwnId) {
                                    val sudahDilihat = viewers.filter { it.lastReadId >= m.id }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 1.dp, end = 4.dp)
                                    ) {
                                        PlnIcon(
                                            PlnIconKind.CHECK,
                                            size = 10.dp,
                                            tint = if (sudahDilihat.isNotEmpty()) e.cyan else e.textMid
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            if (sudahDilihat.isEmpty()) "Terkirim"
                                            else "Dilihat oleh: " +
                                                sudahDilihat.joinToString(", ") { it.readerNama },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = if (sudahDilihat.isNotEmpty()) e.cyan else e.textMid,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                }
                            } else if (p != null) {
                                BubblePending(
                                    p = p,
                                    onRetry = { kirim(p.text, p.localId) }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(6.dp)) }
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
                        .clickable(enabled = bisaKirim) { kirim(draft.trim()) },
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

/* ============================================================
   BANGUN BARIS — kelompokkan pesan beruntun dari pengirim sama
   (jarak < 5 menit, hari sama) menjadi rangkaian rapat.
   ============================================================ */
private fun bangunBaris(
    msgs: List<PlnApi.ChatMessage>,
    pending: List<PendingMsg>,
    isOwn: (PlnApi.ChatMessage) -> Boolean,
    todayKey: String,
): List<ChatRow> {
    data class Info(val row: ChatRow, val sender: String, val day: String, val time: Long)

    fun kunciPengirim(m: PlnApi.ChatMessage): String =
        (if (isOwn(m)) "me" else m.senderType + ":" + m.senderId)

    val dasar = mutableListOf<Info>()
    for (m in msgs) {
        val day = m.createdAt?.take(10) ?: todayKey
        dasar.add(
            Info(
                ChatRow(m.id, m, null, groupStart = false, groupEnd = false, dayChanged = false),
                kunciPengirim(m), day, parseIso(m.createdAt).time
            )
        )
    }
    for (p in pending) {
        dasar.add(
            Info(
                ChatRow(p.localId, null, p, groupStart = false, groupEnd = false, dayChanged = false),
                "pending", todayKey, Long.MAX_VALUE
            )
        )
    }

    return dasar.mapIndexed { i, cur ->
        val prev = dasar.getOrNull(i - 1)
        val next = dasar.getOrNull(i + 1)

        fun berbeda(a: Info?, b: Info?): Boolean {
            if (a == null || b == null) return true
            return a.sender != b.sender || a.day != b.day ||
                (b.time - a.time) > GROUP_GAP_MS
        }

        val start = berbeda(prev, cur)
        val end = berbeda(cur, next)
        val dayChanged = prev == null || prev.day != cur.day
        cur.row.copy(groupStart = start, groupEnd = end, dayChanged = dayChanged)
    }
}

/** "Aktif 5 menit lalu" ringkas untuk subtiter DM. */
private fun relWaktu(iso: String): String {
    if (iso.isBlank()) return "belum lama"
    val d = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.take(19))
    }
    val t = d.getOrNull()?.time ?: return "belum lama"
    val m = ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0)
    return when {
        m < 1 -> "baru saja"
        m < 60 -> "$m menit lalu"
        m < 24 * 60 -> "${m / 60} jam lalu"
        else -> "${m / (24 * 60)} hari lalu"
    }
}

private fun parseIso(iso: String): Date =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.take(19)) ?: Date(0)
    }.getOrDefault(Date(0))

@Composable
private fun Bubble(
    m: PlnApi.ChatMessage,
    own: Boolean,
    showName: Boolean,
    showClock: Boolean,
    clock: String
) {
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
                    modifier = Modifier.padding(start = 6.dp, bottom = 1.dp)
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
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    m.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (own) Color(0xFF241A00) else e.textHi
                )
                if (showClock) {
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
}

/**
 * Gelembung pesan yang belum terkonfirmasi server.
 *  - pending  : emas pudar + jam, teks "Mengirim…"
 *  - gagal    : bisa diketuk untuk kirim ulang, teks merah
 */
@Composable
private fun BubblePending(p: PendingMsg, onRetry: () -> Unit) {
    val e = LocalElectric.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier
                    .graphicsLayer(alpha = if (p.failed) 0.85f else 0.62f)
                    .background(e.goldBrush, RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .then(if (p.failed) Modifier.clickable { onRetry() } else Modifier)
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    p.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF241A00)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 1.dp, end = 4.dp)
            ) {
                PlnIcon(
                    if (p.failed) PlnIconKind.SEND else PlnIconKind.CLOCK,
                    size = 10.dp,
                    tint = if (p.failed) GagalMerah else e.textMid
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    if (p.failed) "Gagal · ketuk untuk kirim ulang" else "Mengirim…",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = if (p.failed) FontWeight.Bold else FontWeight.Normal,
                    color = if (p.failed) GagalMerah else e.textMid
                )
            }
        }
    }
}
