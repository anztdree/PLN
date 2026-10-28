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
    private val READ = 0xFF0E9BC8.toInt() // "Dilihat oleh"

    private lateinit var paneList: LinearLayout
    private lateinit var paneChat: LinearLayout
    private lateinit var rvConvs: RecyclerView
    private lateinit var rvMsgs: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var swipeList: SwipeRefreshLayout

    private val handler = Handler(Looper.getMainLooper())
    private var convs: List<ChatApi.Conv> = emptyList()
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
    /** Kepala panel navy: judul besar + subjudul kecil. */
    private fun header(judul: String, sub: String): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val t = TextView(this).apply {
            text = judul
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val s = TextView(this).apply {
            text = sub
            setTextColor(0xFF9FB0C4.toInt())
            textSize = 12.5f
        }
        h.addView(t)
        h.addView(s)
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
            setBackgroundColor(NAVY)
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(6)
            setPadding(pad, pad, dp(12), pad)
        }
        val back = TextView(this).apply {
            text = "←"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            val p = dp(10)
            setPadding(p, p, p, p)
            setOnClickListener { tampilkanDaftar() }
        }
        head.addView(back)

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

        // hapus SELURUH riwayat percakapan aktif
        val hapus = TextView(this).apply {
            text = "🗑"
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { konfirmasiHapusRiwayat() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) }
        }
        head.addView(hapus)
        paneChat.addView(head, matchW())

        rvMsgs = RecyclerView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        paneChat.addView(
            rvMsgs,
            LinearLayout.LayoutParams(MATCH, MATCH, 1f),
        )

        // ---- bilah tulis ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
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

        btnSend = TextView(this).apply {
            text = "➤"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(NAVY)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(YELLOW) }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
            isEnabled = false
            alpha = 0.5f
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
    // ADAPTER DAFTAR PERCAKAPAN
    // ============================================================
    private inner class ConvAdapter : RecyclerView.Adapter<ConvAdapter.VH>() {

        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(2) // jarak antar baris — baris tidak menempel
                }
                background = GradientDrawable().apply { setColor(CARD) }
            }

            val av = FrameLayout(this@ChatActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
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
            // ikon forum utk baris grup (kotak kuning) — v1.2.0
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
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(RED) }
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

        override fun getItemCount(): Int = convs.size

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = convs[pos]
            val av = h.row.getChildAt(0) as FrameLayout
            val avText = av.getChildAt(0) as TextView
            val dotOn = av.getChildAt(1) as View
            val avIcon = av.getChildAt(2) as ImageView
            val mid = h.row.getChildAt(1) as LinearLayout
            val title = mid.getChildAt(0) as TextView
            val prev = mid.getChildAt(1) as TextView
            val right = h.row.getChildAt(2) as LinearLayout
            val waktu = right.getChildAt(0) as TextView
            val badge = right.getChildAt(1) as TextView

            // v1.2.0: grup = kotak kuning + ikon forum; DM tetap bulat inisial
            val bgAv = avText.background as GradientDrawable
            if (c.kind == "global") {
                avText.visibility = View.GONE
                avIcon.visibility = View.VISIBLE
                avIcon.setImageResource(R.drawable.ic_group_forum)
                bgAv.apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(YELLOW)
                }
            } else {
                avIcon.visibility = View.GONE
                avText.visibility = View.VISIBLE
                avText.text = inisial(c.title)
                avText.textSize = 15f
                bgAv.apply {
                    shape = GradientDrawable.OVAL
                    setColor(NAVY_GRADIENTS[(c.partnerId.hashCode() and 0x7fffffff) % NAVY_GRADIENTS.size])
                }
                avText.setTextColor(Color.WHITE)
            }
            dotOn.visibility = if (c.kind == "dm" && c.online) View.VISIBLE else View.GONE

            // v1.2.0: jarak grup -> daftar petugas lebih lega
            val lp = h.row.layoutParams as RecyclerView.LayoutParams
            lp.topMargin = if (pos > 0 && convs[pos - 1].kind == "global" && c.kind == "dm") dp(10) else 0
            h.row.layoutParams = lp

            title.text = c.title
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
            h.row.setOnClickListener { bukaConv(c) }
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
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(MUTED)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFE1E7EF.toInt()) }
                        val p = dp(10)
                        setPadding(p, dp(3), p, dp(3))
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
                        gravity = Gravity.TOP
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
                        val p = dp(12)
                        setPadding(p, dp(7), p, dp(7))
                    }
                    val isi = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 15f
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
                        1, 3 -> { // pesanku / pending: gelembung kiri, avatar kanan
                            bubble.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginEnd = dp(8)
                            }
                            inner.addView(bubble)
                            inner.addView(avatar)
                        }
                        else -> { // pesan lawan: avatar kiri, gelembung kanan
                            bubble.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = dp(8)
                            }
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
                        vh.bubble.background = GradientDrawable().apply {
                            orientation = GradientDrawable.Orientation.TL_BR
                            colors = intArrayOf(GOLD_A, GOLD_B)
                            cornerRadius = dp(12).toFloat()
                        }
                        vh.isi.setTextColor(INK_OWN)
                        vh.jam.setTextColor(CLOCK_OWN)
                        vh.nama.visibility = View.GONE
                        vh.bubble.setOnClickListener(null)
                        vh.bubble.alpha = 1f

                        // status hanya di pesan admin paling akhir rangkaiannya
                        val lastOwn = synchronized(msgs) { msgs.lastOrNull { it.senderType == "admin" }?.id }
                        if (r.groupEnd && r.m.id == lastOwn) {
                            val pembaca = viewers.filter { it.lastReadId >= r.m.id }
                            vh.statusRow.visibility = View.VISIBLE
                            vh.sIcon.setImageResource(R.drawable.ic_status_check)
                            if (pembaca.isEmpty()) {
                                vh.sIcon.setColorFilter(MUTED)
                                vh.sText.setTextColor(MUTED)
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
                        vh.bubble.background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(CARD)
                            setStroke(dp(1), LINE)
                        }
                        vh.isi.setTextColor(INK)
                        vh.jam.setTextColor(TIME_OTHER)
                        vh.bubble.setOnClickListener(null)
                        vh.bubble.alpha = 1f
                        vh.statusRow.visibility = View.GONE
                        // nama pengirim DI ATAS gelembung (grup, awal rangkaian)
                        if (current?.kind == "global" && r.groupStart) {
                            vh.nama.visibility = View.VISIBLE
                            vh.nama.text = r.m.senderNama
                            vh.nama.setTextColor(NAME_BLUE)
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
                    vh.bubble.background = GradientDrawable().apply {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(GOLD_A, GOLD_B)
                        cornerRadius = dp(12).toFloat()
                    }
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
                        vh.sIcon.setColorFilter(MUTED)
                        vh.sText.setTextColor(MUTED)
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

    // ============================================================
    // UTIL TAMPILAN
    // ============================================================
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
