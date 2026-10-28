package com.pln.admin

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * OBROLAN NATIVE Aplikasi Admin (code 7).
 *
 * Sistem perpesanan penuh DI DALAM APK — bukan lagi tampilan web:
 *   • Daftar percakapan (Diskusi Petugas + pesan pribadi tiap petugas)
 *     lengkap dengan pratinjau, waktu, dan lencana belum dibaca;
 *   • Layar pesan seperti aplikasi pesan: gelembung kiri/kanan,
 *     pemisah tanggal, status online lawan bicara;
 *   • Kirim pesan langsung dari aplikasi (sebagai Admin pusat);
 *   • Pesan masuk memunculkan NOTIFIKASI STATUS BAR (Notify/AdminPoller)
 *     walau aplikasi tertutup, dan mengetuknya membuka layar ini.
 *
 * Seluruh UI dibangun programatik (tanpa XML layout) mengikuti gaya
 * tema aplikasi: navy #0D1B2E + kuning #FFD84D.
 *
 * v1.1.1 (code 6):
 *   • Daftar obrolan TIDAK lagi kehilangan baris "Diskusi Petugas"
 *     setelah riwayat grup dihapus (server kini selalu mengirim objek
 *     global); baris tampil dengan keterangan "Belum ada pesan".
 *   • Ikon ⚡ bulat di samping tombol hapus riwayat dihapus.
 *
 * v1.2.0 (code 7) — disamakan dengan versi Petugas:
 *   • Jarak baris daftar obrolan diperjelas: grup "Diskusi Petugas"
 *     tidak lagi menempel dengan baris petugas di bawahnya.
 *   • Avatar grup diganti KOTAK kuning + ikon forum (dua gelembung) —
 *     jelas terlihat, bukan lagi ⚡ bulat yang nyaris tak tampak.
 *   • Gelembung pesan gaya Petugas: kotak penuh (avatar 32dp di sisi +
 *     gelembung membentang), nama pengirim di ATAS gelembung (grup),
 *     jam di ujung rangkaian pesan beruntun (gap < 5 menit).
 *   • Status pesan admin seperti versi Petugas: "Terkirim" →
 *     "Dilihat oleh: …" di bawah pesan admin terakhir.
 *   • Kirim TANPA menggantung: teks langsung jadi gelembung
 *     "Mengiring…" (pudar) dan berubah jadi pesan sungguhan saat server
 *     OK; gagal → merah "Gagal · ketuk untuk kirim ulang" (bisa retry),
 *     beberapa pesan boleh antre sekaligus.
 *
 * v1.2.1 (code 9) — tema "native apps obrolan":
 *   • Wallpaper obrolan berpola titik lembut (gaya aplikasi pesan).
 *   • Kepala obrolan kaya: avatar grup/pribadi, status "online" hijau,
 *     ikon vektor kembali & hapus riwayat (bukan emoji).
 *   • Gelembung wrap-content (maks ±78% layar) bersudut "ekor" di sisi
 *     avatar + bayangan halus — pendek memuat isi, panjang membungkus.
 *   • Daftar obrolan menjadi kartu membulat berjarak lega.
 *   • Bilah tulis: tombol kirim emas gradasi + ikon pesawat kertas.
 *
 * v1.2.2 (code 10) — rombak tampilan obrolan (masukan pemilik):
 *   • Daftar: LABEL SEKSI "GRUP DISKUSI" & "PESAN PRIBADI · n ONLINE"
 *     bergaya garis + pil — pemisah antar seksi kini benar-benar jelas.
 *   • Ikon lebih hidup: avatar emas gradasi + bayangan, titik online
 *     berdenyut lembut, tombol kepala memantul saat disentuh.
 *   • Wallpaper obrolan DIROMBAK: gradasi navy gelap + titik & petir
 *     PLN samar (gaya aplikasi pesan premium) — gelembung putih
 *     lawan bicara menonjol di atasnya.
 *   • Gelembung kembali MEMBENTANG PENUH mengisi lebar baris (avatar
 *     tetap di sisi), jam rata kanan di dalam gelembung.
 *   • Chip tanggal gelap translusen, nama pengirim emas di grup,
 *     kepala obrolan gradasi navy + garis emas PLN.
 */
class ChatActivity : AppCompatActivity() {

    // ---- tema ----
    private val NAVY = 0xFF0D1B2E.toInt()
    private val NAVY_2 = 0xFF16283F.toInt()
    private val YELLOW = 0xFFFFD84D.toInt()
    private val BG = 0xFFEEF1F5.toInt()
    private val CARD = 0xFFFFFFFF.toInt()
    private val INK = 0xFF131C28.toInt()
    private val MUTED = 0xFF5D6B7F.toInt()
    private val LINE = 0xFFDFE5EC.toInt()
    private val RED = 0xFFE11D48.toInt()
    private val GREEN = 0xFF12924F.toInt()
    private val TIME_OTHER = 0xFF8A97A8.toInt()
    private val NAME_BLUE = 0xFF3D7AB5.toInt()

    // gelembung pesan sendiri — emas PLN seperti tema "Emas" versi Petugas
    private val GOLD_A = 0xFFFFDE21.toInt()
    private val GOLD_B = 0xFFFFB300.toInt()
    private val INK_OWN = 0xFF241A00.toInt()
    private val CLOCK_OWN = 0xB3241A00.toInt()
    private val READ = 0xFF4CC6EC.toInt() // "Dilihat oleh" — terang di latar gelap

    // latar obrolan gelap premium (navy PLN, gaya aplikasi pesan)
    private val CHAT_BG_A = 0xFF0C192B.toInt()
    private val CHAT_BG_B = 0xFF1B2F4A.toInt()
    private val CHIP_DARK = 0x30FFFFFF.toInt()
    private val CHIP_TXT = 0xFFC6D4E4.toInt()
    private val STATUS_DIM = 0xFF9AACBF.toInt() // status di atas latar gelap
    private val NAME_GOLD = 0xFFFFCE3C.toInt()  // nama pengirim di grup
    private val ONLINE = 0xFF39D98A.toInt()
    private val HAIRLINE = 0xFFD8DFE9.toInt()

    private lateinit var paneList: LinearLayout
    private lateinit var paneChat: LinearLayout
    private lateinit var rvConvs: RecyclerView
    private lateinit var rvMsgs: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var headAv: FrameLayout
    private lateinit var headAvText: TextView
    private lateinit var headAvIcon: ImageView
    private lateinit var swipeList: SwipeRefreshLayout
    private lateinit var tvKosong: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var convs: List<ChatApi.Conv> = emptyList()

    /** Butir daftar percakapan: label seksi atau baris percakapan. */
    private sealed class ListItem {
        data class Section(val label: String) : ListItem()
        data class Conv(val c: ChatApi.Conv) : ListItem()
    }
    private var items: List<ListItem> = emptyList()
    private val msgs = ArrayList<ChatApi.ChatMsg>()
    private var current: ChatApi.Conv? = null

    /** Pembaca (chat_reads) percakapan aktif — sumber "Dilihat oleh". */
    private var viewers: List<ChatApi.Viewer> = emptyList()
    private var viewersKey = ""

    /** Antrian pesan yang sedang dikirim / gagal kirim (per percakapan). */
    private data class Pend(
        val localId: Long,
        val convKey: String,
        val text: String,
        val failed: Boolean = false,
    )
    private val pending = ArrayList<Pend>()

    private val convAdapter = ConvAdapter()
    private val msgAdapter = MsgAdapter()

    // Baris tampilan isi pesan: pemisah tanggal, pesan, atau pending
    private sealed class Row {
        data class Tgl(val text: String) : Row()
        data class Pesan(
            val m: ChatApi.ChatMsg,
            val groupStart: Boolean = false,
            val groupEnd: Boolean = false,
            val dayChanged: Boolean = false,
        ) : Row()
        data class Pending(
            val p: Pend,
            val groupStart: Boolean = false,
            val groupEnd: Boolean = false,
            val dayChanged: Boolean = false,
        ) : Row()
    }
    private val rows = ArrayList<Row>()

    private val GROUP_GAP_MS = 5 * 60_000L

    // ---- polling ----
    private val pollLoop = object : Runnable {
        override fun run() {
            val cur = current
            Thread {
                if (cur != null) {
                    val lastId = synchronized(msgs) { msgs.lastOrNull()?.id ?: 0L }
                    val fresh = ChatApi.messages(cur.kind, cur.partnerId, lastId)
                    if (fresh != null) handler.post { terimaPesan(cur, fresh) }
                } else {
                    val pv = ChatApi.previews()
                    if (pv != null) handler.post { renderList(pv.convs) }
                }
            }.start()
            handler.postDelayed(this, if (current != null) 4_000L else 12_000L)
        }
    }

    // ============================================================
    // SIKLUS HIDUP
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(buatPaneDaftar(), match())
        root.addView(buatPanePesan(), match())
        setContentView(root)
        rvConvs.layoutManager = LinearLayoutManager(this)
        rvConvs.adapter = convAdapter
        rvMsgs.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMsgs.adapter = msgAdapter

        etInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { btnSend.isEnabled = s?.isNotBlank() == true }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { kirim(); true } else false
        }
        btnSend.setOnClickListener { kirim() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollLoop)
        handler.post(pollLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollLoop)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (paneChat.visibility == View.VISIBLE) tampilkanDaftar() else finish()
    }

    // ============================================================
    // PANE 1 — DAFTAR PERCAKAPAN
    // ============================================================
    /** Kepala panel: gradasi navy + label emas + judul besar + subjudul
     *  + garis emas PLN — kepala yang berkarakter, bukan polos. */
    private fun header(judul: String, sub: String): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(NAVY, NAVY_2)
            }
            setPadding(dp(18), dp(16), dp(18), dp(14))
            elevation = dp(4).toFloat()
        }
        val over = TextView(this).apply {
            text = "PLN · PUSAT KENDALI"
            setTextColor(YELLOW)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.16f
        }
        val t = TextView(this).apply {
            text = judul
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val s = TextView(this).apply {
            text = sub
            setTextColor(0xFF9FB0C4.toInt())
            textSize = 12.5f
        }
        h.addView(over)
        h.addView(t)
        h.addView(s)
        // garis emas khas PLN di bawah kepala panel — sentuhan tema "Emas"
        h.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(12) }
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(0xFFFFDE21.toInt(), 0xFFFFB300.toInt())
                cornerRadii = floatArrayOf(
                    dp(2).toFloat(), dp(2).toFloat(), dp(2).toFloat(), dp(2).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
        })
        return h
    }

    private fun buatPaneDaftar(): View {
        paneList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        paneList.addView(
            header("Obrolan", "Pesan & diskusi petugas lapangan"),
            matchW(),
        )

        swipeList = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(YELLOW, NAVY)
        }
        rvConvs = RecyclerView(this).apply {
            setBackgroundColor(BG)
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
        }
        swipeList.addView(rvConvs, match())
        // keadaan kosong: belum ada percakapan sama sekali
        tvKosong = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "Belum ada percakapan.\nMulai pesan pribadi dari menu Petugas."
            setTextColor(MUTED)
            textSize = 13.5f
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
        }
        swipeList.addView(tvKosong, match())
        swipeList.setOnRefreshListener {
            Thread {
                val pv = ChatApi.previews()
                handler.post {
                    swipeList.isRefreshing = false
                    if (pv != null) renderList(pv.convs)
                }
            }.start()
        }
        paneList.addView(
            swipeList,
            LinearLayout.LayoutParams(MATCH, MATCH, 1f),
        )
        return paneList
    }

    private fun renderList(list: List<ChatApi.Conv>) {
        convs = list
        // susun butir: seksi GRUP DISKUSI lalu seksi PESAN PRIBADI —
        // label pil antar seksi membuat pemisah jelas sekali
        val out = ArrayList<ListItem>()
        val grup = list.filter { it.kind == "global" }
        val dm = list.filter { it.kind != "global" }
        if (grup.isNotEmpty()) {
            out.add(ListItem.Section("GRUP DISKUSI"))
            grup.forEach { out.add(ListItem.Conv(it)) }
        }
        if (dm.isNotEmpty()) {
            val on = dm.count { it.online }
            out.add(
                ListItem.Section(
                    if (on > 0) "PESAN PRIBADI · $on ONLINE" else "PESAN PRIBADI",
                ),
            )
            dm.forEach { out.add(ListItem.Conv(it)) }
        }
        items = out
        tvKosong.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        convAdapter.notifyDataSetChanged()
    }

    private fun refreshListQuiet() {
        Thread {
            val pv = ChatApi.previews()
            if (pv != null) handler.post { renderList(pv.convs) }
        }.start()
    }

    // ============================================================
    // PANE 2 — ISI PERCAKAPAN
    // ============================================================
    private fun buatPanePesan(): View {
        paneChat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            visibility = View.GONE
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(NAVY, NAVY_2)
            }
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, dp(12), pad)
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            val p = dp(11)
            setPadding(p, p, p, p)
            setOnClickListener { tampilkanDaftar() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            contentDescription = "Kembali ke daftar obrolan"
            bounce()
        }
        head.addView(back)

        // avatar kepala: grup = kotak kuning ikon forum, pribadi = lingkaran inisial
        headAv = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) }
        }
        headAvText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(NAVY_2) }
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        headAvIcon = ImageView(this).apply {
            visibility = View.GONE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        headAv.addView(headAvText)
        headAv.addView(headAvIcon)
        head.addView(headAv)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        tvSub = TextView(this).apply {
            setTextColor(0xFF9FB0C4.toInt())
            textSize = 12f
            maxLines = 1
        }
        mid.addView(tvTitle)
        mid.addView(tvSub)
        head.addView(mid)

        // hapus SELURUH riwayat percakapan aktif — ikon vektor, bukan emoji
        val hapus = ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            val p = dp(9)
            setPadding(p, p, p, p)
            setOnClickListener { konfirmasiHapusRiwayat() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40E11D48.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(4) }
            contentDescription = "Hapus seluruh riwayat chat"
            bounce()
        }
        head.addView(hapus)
        paneChat.addView(head, matchW())
        // garis emas tipis di bawah kepala obrolan — identitas PLN
        paneChat.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(2))
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(0xFFFFDE21.toInt(), 0xFFFFB300.toInt())
                }
            },
            matchW(),
        )

        rvMsgs = RecyclerView(this).apply {
            background = wallpaper() // pola titik lembut gaya aplikasi pesan
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        paneChat.addView(
            rvMsgs,
            LinearLayout.LayoutParams(MATCH, MATCH, 1f),
        )

        // ---- bilah tulis ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            elevation = dp(8).toFloat()
            gravity = Gravity.CENTER_VERTICAL or Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(8) + navSafe())
        }
        etInput = EditText(this).apply {
            hint = "Tulis pesan…"
            textSize = 15f
            maxLines = 4
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFFF1F4F8.toInt())
                setStroke(dp(1), LINE)
            }
            val p = dp(12)
            setPadding(p, dp(9), p, dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(etInput)

        btnSend = ImageView(this).apply {
            setImageResource(R.drawable.ic_compose_send)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(GOLD_A, GOLD_B)
                setStroke(dp(1.5f), 0xFFE0A800.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) }
            isEnabled = false
            alpha = 0.45f
            contentDescription = "Kirim pesan"
            bounce()
        }
        bar.addView(btnSend)
        paneChat.addView(bar, matchW())
        return paneChat
    }

    private fun tampilkanDaftar() {
        paneChat.visibility = View.GONE
        paneList.visibility = View.VISIBLE
        current = null
        tvTitle.text = "Obrolan"
        refreshListQuiet()
    }

    /** Dialog konfirmasi hapus seluruh riwayat percakapan aktif. */
    private fun konfirmasiHapusRiwayat() {
        val c = current ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hapus riwayat chat")
            .setMessage("Hapus SELURUH riwayat \"${c.title}\"?\n\nSemua pesan hilang untuk semua pengguna.")
            .setPositiveButton("Ya, hapus semua") { _, _ -> hapusRiwayat(c) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun hapusRiwayat(c: ChatApi.Conv) {
        Thread {
            val ok = ChatApi.hapusRiwayat(c.kind, c.partnerId)
            handler.post {
                if (ok) {
                    if (current == c) {
                        synchronized(msgs) { msgs.clear() }
                        viewers = emptyList()
                        viewersKey = ""
                        bangunRows()
                        msgAdapter.notifyDataSetChanged()
                    }
                    refreshListQuiet()
                    Toast.makeText(this, "Riwayat chat dihapus", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal menghapus riwayat — periksa koneksi", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun bukaConv(c: ChatApi.Conv) {
        current = c
        synchronized(msgs) { msgs.clear() }
        viewers = emptyList()
        viewersKey = ""
        bangunRows()
        paneList.visibility = View.GONE
        paneChat.visibility = View.VISIBLE
        tvTitle.text = c.title
        tvSub.text = when {
            c.kind == "global" -> "Semua petugas & admin"
            c.online -> "online"
            else -> "pesan pribadi"
        }
        etInput.hint = if (c.kind == "global") "Tulis ke semua petugas…" else "Tulis ke ${c.title}…"
        tvSub.setTextColor(if (c.kind == "dm" && c.online) ONLINE else 0xFF9FB0C4.toInt())
        // avatar kepala: kotak emas gradasi ikon forum (grup) /
        // lingkaran gradasi inisial (pribadi) + cincin hijau saat online
        val bgHead = headAvText.background as GradientDrawable
        if (c.kind == "global") {
            headAvText.visibility = View.GONE
            headAvIcon.visibility = View.VISIBLE
            headAvIcon.setImageResource(R.drawable.ic_group_forum)
            bgHead.apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(11).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(GOLD_A, GOLD_B)
                setStroke(dp(1), 0xFFE0A800.toInt())
            }
        } else {
            headAvIcon.visibility = View.GONE
            headAvText.visibility = View.VISIBLE
            headAvText.text = inisial(c.title)
            val base = NAVY_GRADIENTS[(c.partnerId.hashCode() and 0x7fffffff) % NAVY_GRADIENTS.size]
            bgHead.apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(base, NAVY)
                // cincin hijau saat petugas online — hidup & jelas
                if (c.online) setStroke(dp(2), ONLINE) else setStroke(0, Color.TRANSPARENT)
            }
        }
        msgAdapter.notifyDataSetChanged()
        Thread {
            val page = ChatApi.messages(c.kind, c.partnerId, 0L)
            if (page != null) handler.post { terimaPesan(c, page, penuh = true) }
        }.start()
    }

    /** Terima hasil fetch — diabaikan bila pengguna sudah pindah percakapan. */
    private fun terimaPesan(conv: ChatApi.Conv, page: ChatApi.MsgPage, penuh: Boolean = false) {
        if (current != conv) return
        val vk = page.viewers.joinToString(",") { "${it.readerId}:${it.lastReadId}" }
        val vkBerubah = vk != viewersKey
        if (vkBerubah) {
            viewersKey = vk
            viewers = page.viewers
        }
        val berubah: Boolean = synchronized(msgs) {
            var ada = false
            for (m in page.messages) {
                if (msgs.none { it.id == m.id }) {
                    msgs.add(m)
                    ada = true
                }
            }
            if (penuh) msgs.sortBy { it.id }
            ada
        }
        if (berubah || penuh || vkBerubah) {
            bangunRows()
            msgAdapter.notifyDataSetChanged()
            scrollBottom()
        }
    }

    private fun scrollBottom() {
        rvMsgs.post { rvMsgs.scrollToPosition((rows.size - 1).coerceAtLeast(0)) }
    }

    /**
     * Kirim pesan TANPA menggantung (gaya versi Petugas):
     *  - teks segera dikosongkan dari kolom input dan berpindah menjadi
     *    gelembung "Mengirim…" (lalu jadi pesan sungguhan saat server OK),
     *  - beberapa pesan boleh antre sekaligus,
     *  - gagal -> gelembung merah "Gagal · ketuk untuk kirim ulang".
     */
    private fun kirim(retry: Pend? = null) {
        val c = current ?: return
        val teks = retry?.text ?: etInput.text.toString().trim()
        if (teks.isEmpty()) return
        val localId = retry?.localId ?: -(System.currentTimeMillis())
        val key = convKeyOf(c)
        if (retry == null) etInput.setText("")
        synchronized(pending) {
            pending.removeAll { it.localId == localId }
            pending.add(Pend(localId, key, teks))
        }
        if (current == c) {
            bangunRows()
            msgAdapter.notifyDataSetChanged()
            scrollBottom()
        }
        Thread {
            val m = ChatApi.send(c.kind, c.partnerId, teks)
            handler.post {
                if (m != null) {
                    synchronized(pending) { pending.removeAll { it.localId == localId } }
                    if (current == c) {
                        synchronized(msgs) { if (msgs.none { it.id == m.id }) msgs.add(m) }
                        bangunRows()
                        msgAdapter.notifyDataSetChanged()
                        scrollBottom()
                    }
                } else {
                    synchronized(pending) {
                        val i = pending.indexOfFirst { it.localId == localId }
                        if (i >= 0) pending[i] = pending[i].copy(failed = true)
                    }
                    if (current?.let { convKeyOf(it) } == key) {
                        bangunRows()
                        msgAdapter.notifyDataSetChanged()
                    }
                    Toast.makeText(
                        this,
                        "Pesan gagal terkirim — ketuk gelembungnya untuk mencoba lagi",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun convKeyOf(c: ChatApi.Conv) = "${c.kind}:${c.partnerId}"

    // ============================================================
    // ADAPTER DAFTAR PERCAKAPAN — dengan LABEL SEKSI (garis + pil)
    // "GRUP DISKUSI" / "PESAN PRIBADI · n ONLINE" agar pemisah antar
    // seksi jelas sekali, bukan sekadar jarak.
    // ============================================================
    private inner class ConvAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun getItemViewType(pos: Int): Int =
            if (items[pos] is ListItem.Section) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                // ---- label seksi: garis — PIL — garis ----
                val row = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(6))
                    layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val garisL = View(this@ChatActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    setBackgroundColor(HAIRLINE)
                }
                val pil = TextView(this@ChatActivity).apply {
                    id = View.generateViewId()
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(MUTED)
                    letterSpacing = 0.1f
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0xFFE6ECF4.toInt())
                        setStroke(dp(1), HAIRLINE)
                    }
                    val p = dp(10)
                    setPadding(p, dp(3), p, dp(3))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(9); marginEnd = dp(9) }
                }
                val garisR = View(this@ChatActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    setBackgroundColor(HAIRLINE)
                }
                row.addView(garisL)
                row.addView(pil)
                row.addView(garisR)
                return object : RecyclerView.ViewHolder(row) {}
            }

            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    // kartu membulat berjarak lega (gaya aplikasi pesan native)
                    marginStart = dp(10)
                    marginEnd = dp(10)
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(CARD)
                    setStroke(dp(1), LINE)
                }
                elevation = dp(1.5f).toFloat()
            }

            val av = FrameLayout(this@ChatActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                elevation = dp(2.5f).toFloat() // avatar berbayang — hidup, tak kaku
            }
            val avText = TextView(this@ChatActivity).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(NAVY_2) }
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            }
            av.addView(avText)
            val dotOn = View(this@ChatActivity).apply {
                id = View.generateViewId()
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN); setStroke(dp(2), CARD) }
                layoutParams = FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END)
            }
            av.addView(dotOn)
            // ikon forum utk baris grup (kotak emas gradasi) — v1.2.2
            val avIcon = ImageView(this@ChatActivity).apply {
                id = View.generateViewId()
                visibility = View.GONE
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            }
            av.addView(avIcon)
            row.addView(av)

            val mid = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            }
            val title = TextView(this@ChatActivity).apply {
                id = View.generateViewId()
                setTextColor(NAVY)
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            val prev = TextView(this@ChatActivity).apply {
                id = View.generateViewId()
                setTextColor(MUTED)
                textSize = 13f
                maxLines = 1
            }
            mid.addView(title)
            mid.addView(prev)
            row.addView(mid)

            val right = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(8)
                }
            }
            val waktu = TextView(this@ChatActivity).apply {
                id = View.generateViewId()
                setTextColor(MUTED)
                textSize = 11f
            }
            val badge = TextView(this@ChatActivity).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(20)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(RED)
                    setStroke(dp(1.5f), Color.WHITE)
                }
                val p = dp(5)
                setPadding(p, dp(1), p, dp(1))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            }
            right.addView(waktu)
            right.addView(badge)
            row.addView(right)
            return VH(row)
        }

        override fun getItemCount(): Int = items.size

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            if (h.itemViewType == 0) {
                val pil = (h.itemView as LinearLayout).getChildAt(1) as TextView
                pil.text = (items[pos] as ListItem.Section).label
                return
            }
            val c = (items[pos] as ListItem.Conv).c
            val vh = h as VH
            val av = vh.row.getChildAt(0) as FrameLayout
            val avText = av.getChildAt(0) as TextView
            val dotOn = av.getChildAt(1) as View
            val avIcon = av.getChildAt(2) as ImageView
            val mid = vh.row.getChildAt(1) as LinearLayout
            val title = mid.getChildAt(0) as TextView
            val prev = mid.getChildAt(1) as TextView
            val right = vh.row.getChildAt(2) as LinearLayout
            val waktu = right.getChildAt(0) as TextView
            val badge = right.getChildAt(1) as TextView

            // grup = kotak EMAS GRADASI + ikon forum; DM = lingkaran gradasi inisial
            val bgAv = avText.background as GradientDrawable
            if (c.kind == "global") {
                avText.visibility = View.GONE
                avIcon.visibility = View.VISIBLE
                avIcon.setImageResource(R.drawable.ic_group_forum)
                bgAv.apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(13).toFloat()
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(GOLD_A, GOLD_B)
                    setStroke(dp(1), 0xFFE0A800.toInt())
                }
            } else {
                avIcon.visibility = View.GONE
                avText.visibility = View.VISIBLE
                avText.text = inisial(c.title)
                avText.textSize = 15f
                val base = NAVY_GRADIENTS[(c.partnerId.hashCode() and 0x7fffffff) % NAVY_GRADIENTS.size]
                bgAv.apply {
                    shape = GradientDrawable.OVAL
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(base, NAVY)
                }
                avText.setTextColor(Color.WHITE)
            }
            // titik online: tampil + berdenyut lembut; saat hilang, animasi dihentikan
            if (c.kind == "dm" && c.online) {
                dotOn.visibility = View.VISIBLE
                mulaiPulse(dotOn)
            } else {
                hentikanPulse(dotOn)
                dotOn.visibility = View.GONE
            }

            title.text = c.title
            // pratinjau tebal saat ada pesan belum dibaca — mata langsung menangkapnya
            prev.typeface = if (c.unread > 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            prev.text = when {
                c.lastBody.isEmpty() -> if (c.kind == "dm") "Belum ada pesan — mulai obrolan" else "Belum ada pesan"
                c.kind == "global" && c.lastSenderType == "petugas" -> "${c.lastSenderNama}: ${c.lastBody}"
                else -> c.lastBody
            }
            waktu.text = waktuSingkat(c.lastAt)
            if (c.unread > 0) {
                badge.text = if (c.unread > 99) "99+" else c.unread.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
            vh.row.setOnClickListener { bukaConv(c) }
        }
    }

    // ============================================================
    // ADAPTER ISI PERCAKAPAN — gaya gelembung versi Petugas:
    // kotak penuh (avatar di sisi + gelembung membentang), nama di
    // atas gelembung (grup), jam di ujung rangkaian, status kirim.
    // ============================================================
    private inner class MsgAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(pos: Int): Int = when (val r = rows[pos]) {
            is Row.Tgl -> 0
            is Row.Pesan -> if (r.m.senderType == "admin") 1 else 2
            is Row.Pending -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> {
                    val wrap = FrameLayout(this@ChatActivity).apply {
                        layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                        val v = dp(6)
                        setPadding(0, v, 0, v)
                    }
                    val t = TextView(this@ChatActivity).apply {
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(CHIP_TXT)
                        gravity = Gravity.CENTER
                        // pil gelap translusen — menyatu dengan wallpaper navy
                        background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(CHIP_DARK)
                            setStroke(dp(1), 0x22FFFFFF.toInt())
                        }
                        letterSpacing = 0.06f
                        val p = dp(12)
                        setPadding(p, dp(4), p, dp(4))
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                        )
                    }
                    wrap.addView(t)
                    object : RecyclerView.ViewHolder(wrap) {}
                }
                else -> {
                    // pesan (own/other) & pending — satu struktur
                    val outer = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val nama = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        visibility = View.GONE
                        setPadding(dp(4), 0, dp(4), dp(3))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(40) }
                    }
                    val inner = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        // gelembung membentang PENUH mengisi lebar baris;
                        // avatar tetap menempel di sisi (gaya Petugas)
                        gravity = Gravity.TOP or
                            if (viewType == 1 || viewType == 3) Gravity.END else Gravity.START
                    }
                    val avatar = FrameLayout(this@ChatActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(2) }
                    }
                    val avText = TextView(this@ChatActivity).apply {
                        gravity = Gravity.CENTER
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(NAVY_2) }
                        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    }
                    avatar.addView(avText)
                    val bubble = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        val p = dp(13)
                        setPadding(p, dp(8), p, dp(8))
                        elevation = dp(3).toFloat() // bayangan lembut di wallpaper gelap
                    }
                    val isi = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 15f
                        // gelembung MEMBENTANG PENUH — isi mengikuti lebar
                        setLineSpacing(dp(1.5f).toFloat(), 1f)
                    }
                    val jam = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 10f
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(1) }
                    }
                    bubble.addView(isi)
                    bubble.addView(jam)

                    // baris status di bawah gelembung (own & pending)
                    val sIcon = ImageView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                    }
                    val sText = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 9f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(3) }
                    }
                    val statusRow = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        setPadding(0, dp(1), dp(40), 0)
                        visibility = View.GONE
                    }
                    statusRow.addView(sIcon)
                    statusRow.addView(sText)

                    when (viewType) {
                        1, 3 -> { // pesanku / pending: gelembung penuh, avatar di ujung kanan
                            bubble.layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                            ).apply { marginEnd = dp(6) }
                            inner.addView(bubble)
                            inner.addView(avatar)
                        }
                        else -> { // pesan lawan: avatar kiri, gelembung penuh di kanannya
                            bubble.layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                            ).apply { marginStart = dp(6) }
                            inner.addView(avatar)
                            inner.addView(bubble)
                        }
                    }
                    outer.addView(nama)
                    outer.addView(inner)
                    outer.addView(statusRow)
                    VHMsg(outer, nama, bubble, isi, jam, avText, statusRow, sIcon, sText)
                }
            }
        }

        inner class VHMsg(
            val outer: LinearLayout,
            val nama: TextView,
            val bubble: LinearLayout,
            val isi: TextView,
            val jam: TextView,
            val avText: TextView,
            val statusRow: LinearLayout,
            val sIcon: ImageView,
            val sText: TextView,
        ) : RecyclerView.ViewHolder(outer)

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val r = rows[pos]) {
                is Row.Tgl -> (h.itemView as FrameLayout).getChildAt(0).let { (it as TextView).text = r.text }
                is Row.Pesan -> {
                    val vh = h as VHMsg
                    val own = r.m.senderType == "admin"
                    aturMarginGrup(vh.outer, r.dayChanged, r.groupStart)

                    if (own) {
                        vh.avText.text = inisial(r.m.senderNama.ifBlank { "Admin" })
                        vh.avText.setTextColor(NAVY)
                        (vh.avText.background as GradientDrawable).apply {
                            shape = GradientDrawable.OVAL
                            setColor(YELLOW)
                        }
                        vh.isi.setTextColor(INK_OWN)
                        vh.jam.setTextColor(CLOCK_OWN)
                        vh.nama.visibility = View.GONE
                        vh.bubble.setOnClickListener(null)
                        vh.bubble.alpha = 1f
                        vh.bubble.background = bgBubble(own = true)

                        // status hanya di pesan admin paling akhir rangkaiannya
                        val lastOwn = synchronized(msgs) { msgs.lastOrNull { it.senderType == "admin" }?.id }
                        if (r.groupEnd && r.m.id == lastOwn) {
                            val pembaca = viewers.filter { it.lastReadId >= r.m.id }
                            vh.statusRow.visibility = View.VISIBLE
                            vh.sIcon.setImageResource(R.drawable.ic_status_check)
                            if (pembaca.isEmpty()) {
                                vh.sIcon.setColorFilter(STATUS_DIM)
                                vh.sText.setTextColor(STATUS_DIM)
                                vh.sText.typeface = Typeface.DEFAULT
                                vh.sText.text = "Terkirim"
                            } else {
                                vh.sIcon.setColorFilter(READ)
                                vh.sText.setTextColor(READ)
                                vh.sText.typeface = Typeface.DEFAULT_BOLD
                                vh.sText.text = "Dilihat oleh: " + pembaca.joinToString(", ") { it.readerNama }
                            }
                        } else {
                            vh.statusRow.visibility = View.GONE
                        }
                    } else {
                        vh.avText.text = inisial(r.m.senderNama)
                        vh.avText.setTextColor(Color.WHITE)
                        (vh.avText.background as GradientDrawable).apply {
                            shape = GradientDrawable.OVAL
                            setColor(NAVY_GRADIENTS[(r.m.senderNama.hashCode() and 0x7fffffff) % NAVY_GRADIENTS.size])
                        }
                        vh.isi.setTextColor(INK)
                        vh.jam.setTextColor(TIME_OTHER)
                        vh.bubble.background = bgBubble(own = false)
                        vh.bubble.setOnClickListener(null)
                        vh.bubble.alpha = 1f
                        vh.statusRow.visibility = View.GONE
                        // nama pengirim DI ATAS gelembung (grup, awal rangkaian) —
                        // emas PLN: menonjol di wallpaper navy
                        if (current?.kind == "global" && r.groupStart) {
                            vh.nama.visibility = View.VISIBLE
                            vh.nama.text = r.m.senderNama
                            vh.nama.setTextColor(NAME_GOLD)
                        } else {
                            vh.nama.visibility = View.GONE
                        }
                    }
                    vh.isi.text = r.m.body
                    vh.jam.visibility = if (r.groupEnd) View.VISIBLE else View.GONE
                    vh.jam.text = jamPendek(r.m.createdAt)
                }
                is Row.Pending -> {
                    val vh = h as VHMsg
                    aturMarginGrup(vh.outer, r.dayChanged, r.groupStart)
                    vh.avText.text = "A"
                    vh.avText.setTextColor(NAVY)
                    (vh.avText.background as GradientDrawable).apply {
                        shape = GradientDrawable.OVAL
                        setColor(YELLOW)
                    }
                    vh.bubble.background = bgBubble(own = true)
                    vh.bubble.alpha = if (r.p.failed) 0.85f else 0.62f
                    vh.isi.setTextColor(INK_OWN)
                    vh.isi.text = r.p.text
                    vh.jam.visibility = View.GONE
                    vh.nama.visibility = View.GONE
                    vh.statusRow.visibility = View.VISIBLE
                    if (r.p.failed) {
                        vh.sIcon.setImageResource(R.drawable.ic_status_send)
                        vh.sIcon.setColorFilter(RED)
                        vh.sText.setTextColor(RED)
                        vh.sText.typeface = Typeface.DEFAULT_BOLD
                        vh.sText.text = "Gagal · ketuk untuk kirim ulang"
                        vh.bubble.setOnClickListener { kirim(retry = r.p) }
                    } else {
                        vh.sIcon.setImageResource(R.drawable.ic_status_clock)
                        vh.sIcon.setColorFilter(STATUS_DIM)
                        vh.sText.setTextColor(STATUS_DIM)
                        vh.sText.typeface = Typeface.DEFAULT
                        vh.sText.text = "Mengirim…"
                        vh.bubble.setOnClickListener(null)
                    }
                }
            }
        }

        /** Jarak antar rangkaian pesan: 8dp di awal rangkaian (gaya Petugas). */
        private fun aturMarginGrup(outer: LinearLayout, dayChanged: Boolean, groupStart: Boolean) {
            val lp = outer.layoutParams as RecyclerView.LayoutParams
            lp.topMargin = if (dayChanged) 0 else if (groupStart) dp(8) else 0
            outer.layoutParams = lp
        }
    }

    /** Susun baris tampilan: pemisah tanggal + penanda rangkaian pesan. */
    private fun bangunRows() {
        val todayK = hariKey(Date())
        data class Info(val row: Row, val key: String, val day: String, val t: Long, val iso: String)

        val dasar = ArrayList<Info>()
        synchronized(msgs) {
            for (m in msgs) {
                val own = m.senderType == "admin"
                dasar.add(
                    Info(
                        Row.Pesan(m),
                        if (own) "me" else "o:${m.senderNama}",
                        hariKeyIso(m.createdAt),
                        tgl(m.createdAt)?.time ?: 0L,
                        m.createdAt,
                    )
                )
            }
        }
        val ck = current?.let { convKeyOf(it) } ?: ""
        synchronized(pending) {
            for (p in pending) {
                if (p.convKey != ck) continue
                dasar.add(Info(Row.Pending(p), "pend", todayK, Long.MAX_VALUE, ""))
            }
        }

        val hasil = ArrayList<Row>(dasar.size)
        for (i in dasar.indices) {
            val cur = dasar[i]
            val prev = dasar.getOrNull(i - 1)
            val next = dasar.getOrNull(i + 1)

            fun beda(a: Info?, b: Info?): Boolean =
                a == null || b == null || a.key != b.key || a.day != b.day ||
                    (b.t - a.t) > GROUP_GAP_MS

            val start = beda(prev, cur)
            val end = beda(cur, next)
            val dayChanged = prev == null || prev.day != cur.day
            if (dayChanged) {
                hasil.add(Row.Tgl(if (cur.iso.isBlank()) "Hari Ini" else hariLabel(cur.iso)))
            }
            hasil.add(
                when (val r = cur.row) {
                    is Row.Tgl -> r
                    is Row.Pesan -> r.copy(groupStart = start, groupEnd = end, dayChanged = dayChanged)
                    is Row.Pending -> r.copy(groupStart = start, groupEnd = end, dayChanged = dayChanged)
                }
            )
        }
        rows.clear()
        rows.addAll(hasil)
    }

    // ============================================================
    // WAKTU (zona waktu perangkat, teks Indonesia)
    // Stempel waktu server = ISO-8601 UTC (java.time, minSdk 26)
    // ============================================================
    private val fmtJam = SimpleDateFormat("HH.mm", Locale("id"))
    private val fmtTglSingkat = SimpleDateFormat("dd MMM", Locale("id"))
    private val fmtTglPenuh = SimpleDateFormat("d MMMM yyyy", Locale("id"))

    private fun tgl(iso: String): Date? = try {
        Date.from(java.time.Instant.parse(iso))
    } catch (_: Exception) { null }

    private fun hariKey(d: Date): String {
        val c = Calendar.getInstance().apply { time = d }
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}-${c.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun hariKeyIso(iso: String): String = tgl(iso)?.let { hariKey(it) } ?: "?"

    private fun hariLabel(iso: String): String {
        val d = tgl(iso) ?: return ""
        val c = Calendar.getInstance().apply { time = d }
        val hariIni = Calendar.getInstance()
        val kemarin = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            c.get(Calendar.DAY_OF_YEAR) == hariIni.get(Calendar.DAY_OF_YEAR) &&
                c.get(Calendar.YEAR) == hariIni.get(Calendar.YEAR) -> "Hari Ini"
            c.get(Calendar.DAY_OF_YEAR) == kemarin.get(Calendar.DAY_OF_YEAR) &&
                c.get(Calendar.YEAR) == kemarin.get(Calendar.YEAR) -> "Kemarin"
            else -> fmtTglPenuh.format(d)
        }
    }

    private fun jamPendek(iso: String): String {
        val d = tgl(iso) ?: return ""
        return fmtJam.format(d)
    }

    private fun waktuSingkat(iso: String): String {
        if (iso.isEmpty()) return ""
        val d = tgl(iso) ?: return ""
        return if (hariKey(d) == hariKey(Date())) fmtJam.format(d) else fmtTglSingkat.format(d)
    }

    /** Latar gelembung pesan: gradasi emas (punya) / putih bergaris tipis
     *  (lawan), bersudut "ekor" kecil di sisi avatar seperti aplikasi
     *  pesan native. */
    private fun bgBubble(own: Boolean): GradientDrawable {
        val r = dp(16).toFloat()
        val tail = dp(5).toFloat()
        return GradientDrawable().apply {
            if (own) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(GOLD_A, GOLD_B)
                // urutan: kiri-atas, kanan-atas, kanan-bawah, kiri-bawah —
                // ekor kecil di kanan-atas (sisi avatar admin)
                cornerRadii = floatArrayOf(r, r, tail, tail, r, r, r, r)
            } else {
                setColor(CARD)
                setStroke(dp(1), LINE)
                // ekor kecil di kiri-atas (sisi avatar petugas)
                cornerRadii = floatArrayOf(tail, tail, r, r, r, r, r, r)
            }
        }
    }

    /** Wallpaper obrolan (v1.2.2): gradasi navy gelap + pola titik lembut
     *  + petir PLN samar — gaya aplikasi pesan premium; gelembung putih
     *  lawan bicara menonjol di atasnya. */
    private fun wallpaper(): android.graphics.drawable.Drawable {
        val grad = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(CHAT_BG_A, CHAT_BG_B)
        }
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint().apply { isAntiAlias = true }
        // titik-titik lembut
        p.color = 0x16FFFFFF.toInt()
        c.drawCircle(14f, 20f, 2.2f, p)
        c.drawCircle(58f, 66f, 2.2f, p)
        // kilau emas kecil
        p.color = 0x24FFC93C.toInt()
        c.drawCircle(70f, 18f, 1.7f, p)
        c.drawCircle(24f, 74f, 1.7f, p)
        // petir PLN samar sebagai identitas tema
        p.color = 0x0FFFFFFF.toInt()
        val bolt = android.graphics.Path().apply {
            moveTo(46f, 30f)
            lineTo(36f, 52f)
            lineTo(44f, 52f)
            lineTo(39f, 70f)
            lineTo(54f, 46f)
            lineTo(45f, 46f)
            lineTo(52f, 30f)
            close()
        }
        c.drawPath(bolt, p)
        val dots = android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(grad, dots))
    }

    // ============================================================
    // UTIL TAMPILAN
    // ============================================================
    /** Denyut lembut titik online — kesan hidup, bukan ikon kaku. */
    private fun mulaiPulse(v: View) {
        hentikanPulse(v)
        val sx = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f, 1f)
        val sy = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f, 1f)
        v.tag = android.animation.ObjectAnimator.ofPropertyValuesHolder(v, sx, sy).apply {
            duration = 1100
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun hentikanPulse(v: View) {
        (v.tag as? android.animation.ObjectAnimator)?.cancel()
        v.tag = null
        v.scaleX = 1f
        v.scaleY = 1f
    }

    /** Umpan balik sentuh "memantul" untuk tombol ikon — terasa responsif. */
    private fun View.bounce() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false // klik tetap berjalan
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private fun match() = FrameLayout.LayoutParams(MATCH, MATCH)
    private fun matchW() = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun navSafe(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun inisial(nama: String): String {
        val parts = nama.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        return if (parts.size == 1) parts[0].take(1).uppercase()
        else (parts[0].take(1) + parts[parts.size - 1].take(1)).uppercase()
    }

    companion object {
        private val NAVY_GRADIENTS = intArrayOf(
            0xFF12365E.toInt(), 0xFF1F5487.toInt(), 0xFF7A5C14.toInt(),
            0xFF14532D.toInt(), 0xFF5B2333.toInt(), 0xFF262D3A.toInt(),
        )

        /** Buka obrolan dari notifikasi / FAB. */
        fun buka(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }
}
