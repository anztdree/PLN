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
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
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
 * OBROLAN NATIVE Aplikasi Admin (code 5).
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
    private val TIME_OWN = 0xFF93A5BB.toInt()
    private val TIME_OTHER = 0xFF8A97A8.toInt()
    private val NAME_BLUE = 0xFF3D7AB5.toInt()

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
    private var sending = false

    private val convAdapter = ConvAdapter()
    private val msgAdapter = MsgAdapter()

    // Baris tampilan isi pesan: pemisah tanggal ATAU pesan
    private sealed class Row {
        data class Tgl(val text: String) : Row()
        data class Pesan(val m: ChatApi.ChatMsg) : Row()
    }
    private val rows = ArrayList<Row>()

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
        rvConvs = RecyclerView(this).apply { setBackgroundColor(BG) }
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
        if (paneChat.visibility != View.VISIBLE) {
            // judul header ikut menampilkan jumlah pesan belum dibaca
        }
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

        // hapus SELURUH riwayat percakapan aktif (code 5)
        val hapus = TextView(this).apply {
            text = "🗑"
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { konfirmasiHapusRiwayat() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) }
        }
        head.addView(hapus)

        // titik avatar bulat di kanan header
        val dot = TextView(this).apply {
            text = "⚡"
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(YELLOW) }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }
        head.addView(dot)
        paneChat.addView(head, matchW())

        rvMsgs = RecyclerView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = true
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
            val m = ChatApi.messages(c.kind, c.partnerId, 0L)
            if (m != null) handler.post { terimaPesan(c, m, penuh = true) }
        }.start()
    }

    /** Terima hasil fetch — diabaikan bila pengguna sudah pindah percakapan. */
    private fun terimaPesan(conv: ChatApi.Conv, fresh: List<ChatApi.ChatMsg>, penuh: Boolean = false) {
        if (current != conv) return
        val berubah: Boolean = synchronized(msgs) {
            var ada = false
            for (m in fresh) {
                if (msgs.none { it.id == m.id }) {
                    msgs.add(m)
                    ada = true
                }
            }
            if (penuh) msgs.sortBy { it.id }
            ada
        }
        if (berubah || penuh) {
            bangunRows()
            msgAdapter.notifyDataSetChanged()
            rvMsgs.post { rvMsgs.scrollToPosition((rows.size - 1).coerceAtLeast(0)) }
        }
    }

    private fun kirim() {
        val c = current ?: return
        val teks = etInput.text.toString().trim()
        if (teks.isEmpty() || sending) return
        sending = true
        btnSend.isEnabled = false
        Thread {
            val m = ChatApi.send(c.kind, c.partnerId, teks)
            handler.post {
                sending = false
                if (m != null) {
                    etInput.setText("")
                    synchronized(msgs) {
                        if (msgs.none { it.id == m.id }) msgs.add(m)
                    }
                    bangunRows()
                    msgAdapter.notifyDataSetChanged()
                    rvMsgs.post { rvMsgs.scrollToPosition((rows.size - 1).coerceAtLeast(0)) }
                } else {
                    Toast.makeText(this, "Gagal mengirim — periksa koneksi", Toast.LENGTH_SHORT).show()
                    btnSend.isEnabled = etInput.text.isNotBlank()
                }
            }
        }.start()
    }

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
                layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            val mid = h.row.getChildAt(1) as LinearLayout
            val title = mid.getChildAt(0) as TextView
            val prev = mid.getChildAt(1) as TextView
            val right = h.row.getChildAt(2) as LinearLayout
            val waktu = right.getChildAt(0) as TextView
            val badge = right.getChildAt(1) as TextView

            if (c.kind == "global") {
                avText.text = "⚡"
                avText.textSize = 18f
                (avText.background as GradientDrawable).setColor(YELLOW)
                avText.setTextColor(NAVY)
            } else {
                avText.text = inisial(c.title)
                avText.textSize = 15f
                (avText.background as GradientDrawable).setColor(
                    NAVY_GRADIENTS[(c.partnerId.hashCode() and 0x7fffffff) % NAVY_GRADIENTS.size]
                )
                avText.setTextColor(Color.WHITE)
            }
            dotOn.visibility = if (c.kind == "dm" && c.online) View.VISIBLE else View.GONE

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
    // ADAPTER ISI PERCAKAPAN
    // ============================================================
    private inner class MsgAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(pos: Int): Int = when (rows[pos]) {
            is Row.Tgl -> 0
            is Row.Pesan -> if ((rows[pos] as Row.Pesan).m.senderType == "admin") 1 else 2
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
                    val wrap = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = RecyclerView.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setPadding(dp(10), dp(2), dp(10), dp(2))
                    }
                    val bubble = LinearLayout(this@ChatActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            val m = dp(30)
                            marginEnd = m
                            marginStart = m
                        }
                        val p = dp(11)
                        setPadding(p, dp(7), p, dp(7))
                    }
                    val nama = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                    }
                    val isi = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 15f
                        setLineSpacing(dp(1.5f).toFloat(), 1f)
                        // gelembung maksimal ±78% lebar layar (TextView punya maxWidth)
                        maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                    }
                    val jam = TextView(this@ChatActivity).apply {
                        id = View.generateViewId()
                        textSize = 10f
                        gravity = Gravity.END
                    }
                    bubble.addView(nama)
                    bubble.addView(isi)
                    bubble.addView(jam)
                    wrap.addView(bubble)
                    VHMsg(wrap, bubble, nama, isi, jam)
                }
            }
        }

        inner class VHMsg(
            val wrap: LinearLayout,
            val bubble: LinearLayout,
            val nama: TextView,
            val isi: TextView,
            val jam: TextView,
        ) : RecyclerView.ViewHolder(wrap)

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val r = rows[pos]) {
                is Row.Tgl -> (h.itemView as FrameLayout).getChildAt(0).let { (it as TextView).text = r.text }
                is Row.Pesan -> {
                    val vh = h as VHMsg
                    val own = r.m.senderType == "admin"
                    if (own) {
                        (vh.wrap as LinearLayout).gravity = Gravity.END
                        vh.bubble.background = GradientDrawable().apply {
                            cornerRadius = dp(16).toFloat()
                            cornerSpecificRadius(dp(16), dp(4), dp(16), dp(16)) // ekor kanan atas rata
                            setColor(NAVY)
                        }
                        vh.isi.setTextColor(Color.WHITE)
                        vh.jam.setTextColor(TIME_OWN)
                        vh.nama.visibility = View.GONE
                    } else {
                        (vh.wrap as LinearLayout).gravity = Gravity.START
                        vh.bubble.background = GradientDrawable().apply {
                            cornerRadius = dp(16).toFloat()
                            cornerSpecificRadius(dp(16), dp(16), dp(4), dp(16)) // ekor kiri atas rata
                            setColor(CARD)
                            setStroke(dp(1), LINE)
                        }
                        vh.isi.setTextColor(INK)
                        vh.jam.setTextColor(TIME_OTHER)
                        if (current?.kind == "global") {
                            vh.nama.visibility = View.VISIBLE
                            vh.nama.text = r.m.senderNama
                            vh.nama.setTextColor(NAME_BLUE)
                        } else {
                            vh.nama.visibility = View.GONE
                        }
                    }
                    vh.isi.text = r.m.body
                    vh.jam.text = jamPendek(r.m.createdAt)
                }
            }
        }

        /** Sudut per-tikung: topLeft, topRight, bottomRight, bottomLeft (dp). */
        fun GradientDrawable.cornerSpecificRadius(tl: Int, tr: Int, br: Int, bl: Int) {
            cornerRadii = floatArrayOf(
                tl.toFloat(), tl.toFloat(),
                tr.toFloat(), tr.toFloat(),
                br.toFloat(), br.toFloat(),
                bl.toFloat(), bl.toFloat(),
            )
        }
    }

    /** Susun baris tampilan: sisipkan pemisah tanggal tiap pergantian hari. */
    private fun bangunRows() {
        synchronized(msgs) {
            rows.clear()
            var lastKey = ""
            for (m in msgs) {
                val k = hariKeyIso(m.createdAt)
                if (k != lastKey) {
                    rows.add(Row.Tgl(hariLabel(m.createdAt)))
                    lastKey = k
                }
                rows.add(Row.Pesan(m))
            }
        }
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
