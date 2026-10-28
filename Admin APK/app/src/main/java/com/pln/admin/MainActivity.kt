package com.pln.admin

import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * PLN Admin — pembungkus aplikasi Android untuk PWA Website Admin.
 * Menampilkan dasbor penuh tanpa bilah alamat peramban, mendukung
 * unduhan Excel/PDF lewat DownloadManager sistem, dan menampilkan
 * notifikasi status bar untuk pesan & laporan baru.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private val handler = Handler(Looper.getMainLooper())
    private var pollerRunning = false

    private val pollLoop = object : Runnable {
        override fun run() {
            Thread {
                val changed = AdminPoller.poll(this@MainActivity, notify = false)
                if (changed) {
                    // segarkan halaman agar lencana & angka terbaru tampil
                    handler.post { if (!isDestroyed && !isFinishing) web.reload() }
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

        refresh = SwipeRefreshLayout(this)
        web = WebView(this)
        refresh.addView(web)
        setContentView(refresh)

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
            handler.postDelayed(pollLoop, 3_000)
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
