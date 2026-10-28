package com.pln.admin

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lapisan data OBROLAN NATIVE (v1.1.0 / code 4) — Aplikasi Admin.
 *
 * Obrolan kini bukan lagi tampilan web: daftar percakapan, isi pesan,
 * dan pengiriman semuanya lewat API pusat langsung dari aplikasi
 * (seperti WhatsApp). Endpoint yang sama dipakai web & Aplikasi Petugas:
 *   - GET  /api/chat/previews   -> daftar percakapan + belum dibaca
 *   - GET  /api/chat/messages   -> isi percakapan (membuka = menandai dibaca)
 *   - POST /api/chat/send       -> kirim pesan sebagai ADMIN pusat
 *   - DEL  /api/chat/history    -> hapus SELURUH riwayat satu percakapan
 */
object ChatApi {

    /** Satu pesan obrolan. */
    data class ChatMsg(
        val id: Long,
        val senderType: String, // 'admin' | 'petugas'
        val senderNama: String,
        val body: String,
        val createdAt: String,
    )

    /** Pembaca pesan (chat_reads) — sumber status "Dilihat oleh". */
    data class Viewer(
        val readerId: String,
        val readerNama: String,
        val lastReadId: Long,
    )

    /** Hasil satu kali ambil isi percakapan: pesan + daftar pembaca. */
    data class MsgPage(val messages: List<ChatMsg>, val viewers: List<Viewer>)

    /** Satu baris daftar percakapan. */
    data class Conv(
        val kind: String, // 'global' | 'dm'
        val partnerId: String, // '' utk global, id petugas utk dm
        val title: String,
        val unread: Int,
        val lastBody: String,
        val lastSenderNama: String,
        val lastSenderType: String,
        val lastAt: String,
        val online: Boolean,
    )

    data class Previews(val convs: List<Conv>, val unreadTotal: Int)

    private fun http(urlStr: String, method: String, body: String?): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = method
                setRequestProperty("X-API-Key", Server.API_KEY)
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.let(::JSONObject)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Daftar percakapan: Diskusi Petugas + DM pribadi tiap petugas. */
    fun previews(): Previews? {
        val j = http("${Server.BASE_URL}/api/chat/previews", "GET", null) ?: return null
        if (!j.optBoolean("ok")) return null
        val unread = j.optJSONObject("unread") ?: JSONObject()
        val dm = j.optJSONObject("dm") ?: JSONObject()

        val convs = ArrayList<Conv>()
        j.optJSONObject("global")?.let { g ->
            convs.add(
                Conv(
                    kind = "global",
                    partnerId = "",
                    title = "Diskusi Petugas",
                    unread = unread.optInt("global", 0),
                    lastBody = g.optString("body"),
                    lastSenderNama = g.optString("sender_nama"),
                    lastSenderType = g.optString("sender_type"),
                    lastAt = g.optString("created_at"),
                    online = false,
                )
            )
        }
        val ptg = j.optJSONArray("petugas") ?: org.json.JSONArray()
        for (i in 0 until ptg.length()) {
            val p = ptg.optJSONObject(i) ?: continue
            val id = p.optString("id")
            if (id.isEmpty()) continue
            val m = dm.optJSONObject(id)
            convs.add(
                Conv(
                    kind = "dm",
                    partnerId = id,
                    title = p.optString("nama", "Petugas"),
                    unread = unread.optInt(id, 0),
                    lastBody = m?.optString("body").orEmpty(),
                    lastSenderNama = m?.optString("sender_nama").orEmpty(),
                    lastSenderType = m?.optString("sender_type").orEmpty(),
                    lastAt = m?.optString("created_at").orEmpty(),
                    online = p.optBoolean("online"),
                )
            )
        }
        // Percakapan terbaru di atas; yang belum bersuala mengikuti berdasar nama.
        convs.sortWith(
            compareByDescending<Conv> { it.lastAt }.thenBy { it.title }
        )
        return Previews(convs, j.optInt("unread_total", 0))
    }

    /**
     * Isi percakapan. `after` = id pesan terakhir yang sudah dimiliki
     * (0 = ambil terbaru). Membuka percakapan lewat header X-API-Key
     * otomatis MENANDAI SEMUA PESAN TERBACA di server (sumber lencana).
     * Sekaligus mengembalikan daftar pembaca (chat_reads) untuk status
     * "Terkirim / Dilihat oleh" di bawah pesan admin terakhir.
     */
    fun messages(kind: String, partnerId: String, after: Long): MsgPage? {
        val url = if (kind == "global") {
            "${Server.BASE_URL}/api/chat/messages?scope=global&after=$after&limit=300"
        } else {
            val a = URLEncoder.encode("admin", "UTF-8")
            val b = URLEncoder.encode(partnerId, "UTF-8")
            "${Server.BASE_URL}/api/chat/messages?scope=private&a=$a&b=$b&after=$after&limit=300"
        }
        val j = http(url, "GET", null) ?: return null
        if (!j.optBoolean("ok")) return null
        val arr = j.optJSONArray("messages") ?: return MsgPage(emptyList(), emptyList())
        val out = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            out.add(
                ChatMsg(
                    id = m.optLong("id"),
                    senderType = m.optString("sender_type"),
                    senderNama = m.optString("sender_nama"),
                    body = m.optString("body"),
                    createdAt = m.optString("created_at"),
                )
            )
        }
        val vw = ArrayList<Viewer>()
        val va = j.optJSONArray("viewers")
        if (va != null) {
            for (i in 0 until va.length()) {
                val v = va.optJSONObject(i) ?: continue
                vw.add(
                    Viewer(
                        readerId = v.optString("reader_id"),
                        readerNama = v.optString("reader_nama"),
                        lastReadId = v.optLong("last_read_id"),
                    )
                )
            }
        }
        return MsgPage(out, vw)
    }

    /** Kirim pesan sebagai ADMIN pusat. Mengembalikan pesan tersimpan. */
    fun send(kind: String, partnerId: String, text: String): ChatMsg? {
        val payload = JSONObject().apply {
            put("scope", if (kind == "global") "global" else "private")
            put("sender_type", "admin")
            if (kind != "global") put("target_id", partnerId)
            put("body", text)
        }
        val j = http("${Server.BASE_URL}/api/chat/send", "POST", payload.toString()) ?: return null
        if (!j.optBoolean("ok")) return null
        val m = j.optJSONObject("message") ?: return null
        return ChatMsg(
            id = m.optLong("id"),
            senderType = m.optString("sender_type", "admin"),
            senderNama = m.optString("sender_nama"),
            body = m.optString("body"),
            createdAt = m.optString("created_at"),
        )
    }

    /**
     * Hapus SELURUH riwayat satu percakapan (code 5).
     * kind = 'global' -> seluruh Diskusi Petugas; 'dm' -> DM admin & partnerId.
     * Pesan hilang untuk semua pengguna; lencana & "dilihat oleh" mulai dari nol.
     */
    fun hapusRiwayat(kind: String, partnerId: String): Boolean {
        val url = if (kind == "global") {
            "${Server.BASE_URL}/api/chat/history?scope=global"
        } else {
            val a = URLEncoder.encode("admin", "UTF-8")
            val b = URLEncoder.encode(partnerId, "UTF-8")
            "${Server.BASE_URL}/api/chat/history?scope=private&a=$a&b=$b"
        }
        val j = http(url, "DELETE", null) ?: return false
        return j.optBoolean("ok")
    }
}
