package com.pln.admin

import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * PLN Admin — pembungkus aplikasi Android untuk Website Admin.
 * Menampilkan dasbor penuh tanpa bilah alamat peramban, mendukung
 * unduhan Excel/PDF lewat DownloadManager sistem, menampilkan
 * notifikasi status bar untuk pesan & laporan baru, plus TOMBOL
 * OBROLAN MELAYANG (FAB) NATIVE yang membuka OBROLAN NATIVE penuh
 * di dalam aplikasi (ChatActivity) — seperti WhatsApp.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var fab: FrameLayout
    private lateinit var fabBadge: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pollerRunning = false

    private val pollLoop = object : Runnable {
        override fun run() {
            Thread {
                // notify = true: aplikasi selalu siap memberi notifikasi
                // saat ada pesan masuk, bukan hanya saat aplikasi ditutup.
                val hasil = AdminPoller.poll(this@MainActivity, notify = true)
                handler.post {
                    if (!isDestroyed && !isFinishing) tampilkanBadge(hasil.unread)
                }
            }.start()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.ensureChannel(this)
        mintaIzinNotifikasi()
        AdminPollWorker.schedule(this)

        val root = FrameLayout(this)
        refresh = SwipeRefreshLayout(this)
        web = WebView(this)
        refresh.addView(
            web,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            refresh,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        buatFab(root)

        refresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.yellow),
            ContextCompat.getColor(this, R.color.navy),
        )
        refresh.setOnRefreshListener {
            web.reload()
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString PLNAdminApp/1.0"
        }

        // Dialog JavaScript web (konfirmasi hapus dsb.) harus ditangani
        // WebChromeClient — tanpa ini confirm() web DIAM SAJA di WebView
        // dan tombol hapus petugas/pesan tidak berfungsi.
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                val input = android.widget.EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                    val p = dp(12)
                    setPadding(p, p, p, p)
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // tautan keluar (Google Maps dkk.) biarkan aplikasi terkait yang membuka
                return url.startsWith("http") &&
                    !url.startsWith("https://pln-admin.andri-12744.workers.dev") &&
                    bukaDiLuar(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                refresh.isRefreshing = false
            }
        }

        // Unduhan Excel/PDF: serahkan ke DownloadManager sistem
        web.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) return@DownloadListener // sudah ditangani web via tautan langsung
            unduh(url, contentDisposition, mimeType)
        })

        if (savedInstanceState == null) {
            web.loadUrl(Server.BASE_URL)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    /** Tombol obrolan melayang NATIVE gaya aplikasi pesan: lingkaran
     *  emas gradasi PLN (senada gelembung pesan admin) berbingkai navy,
     *  kilau lembut di sekeliling, animasi tekan memantul, dan lencana
     *  jumlah pesan belum dibaca. Klik membuka OBROLAN NATIVE
     *  (ChatActivity) — bukan tampilan web. */
    private fun buatFab(root: FrameLayout) {
        fab = FrameLayout(this).apply {
            // Melayang DI ATAS bilah navigasi bawah milik web (64dp) — bukan
            // menimpanya, agar tidak menutup tab Laporan.
            val lp = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END)
            lp.rightMargin = dp(20)
            lp.bottomMargin = dp(64 + 16) // tinggi bilah nav web + jarak 16dp
            layoutParams = lp
            // Badan: emas gradasi + bingkai navy tipis — kontras kuat di
            // dasbor gelap dan serasi tema "Emas" obrolan.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFFFFDE21.toInt(), 0xFFFFB300.toInt())
                setStroke(dp(2), 0xFF0D1B2E.toInt())
            }
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Buka obrolan"
        }

        // Kilau lembut (halo) melingkar di sekeliling tombol —
        // memancar keluar dari badan 58dp, butuh pemetongan mati.
        val halo = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2EFFC400.toInt())
            }
            isClickable = false
            isFocusable = false
        }
        fab.addView(halo)

        val ic = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            setImageResource(R.drawable.ic_fab_chat)
            setColorFilter(0xFF0D1B2E.toInt()) // ikon navy di atas emas
        }
        fab.addView(ic)

        fabBadge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP,
            )
            translationY = -dp(6).toFloat()
            translationX = dp(6).toFloat()
            minWidth = dp(20)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFFE11D48.toInt())
                setStroke(dp(1.5f), Color.WHITE)
            }
            setPadding(dp(5), dp(2), dp(5), dp(2))
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        fab.addView(fabBadge)

        // Pemetongan dimatikan di FAB DAN induknya — tanpa ini kilau dan
        // lencana yang keluar dari lingkaran terpotong.
        fab.clipChildren = false
        fab.clipToPadding = false
        root.clipChildren = false

        // Animasi tekan gaya aplikasi chat modern: mengecil saat disentuh,
        // memantul balik sedikit melebihi ukuran saat dilepas.
        fab.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            false // tetap serahkan ke onClick
        }
        fab.setOnClickListener { ChatActivity.buka(this) }
        root.addView(fab)
    }

    private fun tampilkanBadge(n: Int) {
        if (n > 0) {
            fabBadge.text = if (n > 99) "99+" else n.toString()
            fabBadge.visibility = View.VISIBLE
        } else {
            fabBadge.visibility = View.GONE
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v,
        resources.displayMetrics,
    ).toInt()

    private fun bukaDiLuar(url: String): Boolean {
        return try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun namaBerkas(url: String, contentDisposition: String?): String {
        contentDisposition?.let { cd ->
            Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(cd)?.groupValues?.get(1)?.let { return it.trim() }
        }
        val path = Uri.parse(url).lastPathSegment.orEmpty()
        return if (path.contains("month.xlsx")) "Laporan-PLN.xlsx"
        else if (path.contains("month.pdf")) "Laporan-PLN.pdf"
        else path.substringAfterLast('/').ifEmpty { "unduhan-pln" }
    }

    private fun unduh(url: String, contentDisposition: String?, mimeType: String?) {
        val nama = namaBerkas(url, contentDisposition)
        val ekst = nama.substringAfterLast('.', "").lowercase()
        val mime = when (mimeType?.ifEmpty { null } ?: ekst) {
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pdf", "application/pdf" -> "application/pdf"
            else -> mimeType ?: "application/octet-stream"
        }
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mime)
            setTitle(nama)
            setDescription("Mengunduh dari PLN Admin")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nama)
            addRequestHeader("X-API-Key", Server.API_KEY)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
        }
        try {
            getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(this, "Mengunduh $nama…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unduhan gagal: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mintaIzinNotifikasi() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!pollerRunning) {
            pollerRunning = true
            handler.post(pollLoop) // segarkan lencana segera (termasuk kembali dari Obrolan)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollLoop)
        pollerRunning = false
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }
}
