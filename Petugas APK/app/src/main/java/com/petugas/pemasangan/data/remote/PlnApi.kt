package com.petugas.pemasangan.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Klien ringan untuk layanan pusat (M4): registrasi, heartbeat, daftar
 * petugas, dan obrolan. Pakai HttpURLConnection + org.json bawaan Android —
 * tanpa library tambahan, sama seperti [ReportUploader].
 *
 * Semua pemanggilan berjalan di Dispatchers.IO dan mengembalikan [Result]
 * sehingga layar cukup menampilkan sukses/gagal tanpa crash.
 */
object PlnApi {

    /* ================= MODEL ================= */

    data class RegisterResult(val petugasId: Long, val nama: String, val restored: Boolean)

    data class PetugasItem(
        val id: Long,
        val nama: String,
        val online: Boolean,
        val lastSeenAt: String,
    )

    data class ChatMessage(
        val id: Long,
        val senderType: String,   // "petugas" | "admin"
        val senderId: String,
        val senderNama: String,
        val targetId: String?,    // null utk global
        val body: String,
        val createdAt: String,
    )

    /** Pembaca percakapan: sudah melihat semua pesan dgn id <= lastReadId. */
    data class Viewer(
        val readerId: String,
        val readerNama: String,
        val lastReadId: Long,
    )

    data class MessagesResult(
        val messages: List<ChatMessage>,
        val viewers: List<Viewer>,
    )

    /** Satu percakapan pada ringkasan notifikasi. */
    data class SummaryConv(
        val key: String,          // "global" | "9|admin" | "3|9"
        val title: String,        // nama lawan bicara / "Obrolan Umum"
        val partner: String,      // "global" | "admin" | id petugas
        val lastId: Long,
        val lastBody: String,
        val lastAt: String,
        val lastSenderType: String,
        val lastSenderId: String,
        val lastSenderNama: String,
    )

    data class SummaryResult(
        val meId: Long,
        val meNama: String,
        val convs: List<SummaryConv>,
    )

    /* ================= REGISTRASI ================= */

    suspend fun register(
        nama: String,
        deviceId: String,
        androidId: String,
    ): Result<RegisterResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("nama", nama)
                put("device_id", deviceId)
                put("android_id", androidId)
            }
            val json = postJson(ServerConfig.REGISTER_URL, body)
            if (!json.optBoolean("ok")) apiError(json)
            val p = json.getJSONObject("petugas")
            RegisterResult(
                petugasId = p.getLong("id"),
                nama = p.getString("nama"),
                // "restored" = perangkat dikenal; "rebound" = daftar ulang
                // setelah reset data/install ulang dgn nama yang sama.
                restored = json.optString("status") == "restored" ||
                    json.optString("status") == "rebound",
            )
        }
    }

    /* ================= HEARTBEAT ================= */

    suspend fun heartbeat(deviceId: String, androidId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("android_id", androidId)
                }
                val json = postJson(ServerConfig.HEARTBEAT_URL, body)
                if (!json.optBoolean("ok")) apiError(json)
                Unit
            }
        }

    /* ================= DAFTAR PETUGAS ================= */

    suspend fun fetchPetugas(): Result<List<PetugasItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = get(ServerConfig.PETUGAS_URL)
            val json = JSONObject(readBody(conn))
            conn.disconnect()
            if (!json.optBoolean("ok")) apiError(json)
            val arr = json.optJSONArray("petugas") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PetugasItem(
                    id = o.getLong("id"),
                    nama = o.optString("nama"),
                    online = o.optBoolean("online"),
                    lastSeenAt = o.optString("last_seen_at"),
                )
            }
        }
    }

    /* ================= OBROLAN ================= */

    /**
     * Ambil pesan.
     * @param scope "global" atau "private"
     * @param a     utk private: pihak 1 (id petugas saya)
     * @param b     utk private: pihak 2 (id petugas lawan atau "admin")
     * @param after 0 = terbaru (maks 100), >0 = hanya pesan baru setelah id ini
     */
    suspend fun fetchMessages(
        scope: String,
        a: String = "",
        b: String = "",
        after: Long,
        myDeviceId: String,
        myAndroidId: String,
    ): Result<MessagesResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildString {
                append(ServerConfig.CHAT_MESSAGES_URL)
                append("?scope=").append(scope)
                if (scope == "private") {
                    append("&a=").append(URLEncoder.encode(a, "UTF-8"))
                    append("&b=").append(URLEncoder.encode(b, "UTF-8"))
                }
                append("&after=").append(after)
                append("&limit=100")
            }
            val conn = get(url, myDeviceId, myAndroidId)
            val json = JSONObject(readBody(conn))
            conn.disconnect()
            if (!json.optBoolean("ok")) apiError(json)
            val arr = json.optJSONArray("messages") ?: JSONArray()
            val pesan = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChatMessage(
                    id = o.getLong("id"),
                    senderType = o.optString("sender_type"),
                    senderId = o.optString("sender_id"),
                    senderNama = o.optString("sender_nama"),
                    targetId = if (o.isNull("target_id")) null else o.optString("target_id"),
                    body = o.optString("body"),
                    createdAt = o.optString("created_at"),
                )
            }
            // Pembaca lain percakapan ini (status "dilihat oleh")
            val vArr = json.optJSONArray("viewers") ?: JSONArray()
            val viewers = (0 until vArr.length()).mapNotNull { i ->
                val o = vArr.optJSONObject(i) ?: return@mapNotNull null
                Viewer(
                    readerId = o.optString("reader_id"),
                    readerNama = o.optString("reader_nama"),
                    lastReadId = o.optLong("last_read_id"),
                )
            }
            MessagesResult(pesan, viewers)
        }
    }

    /** Kirim pesan teks. Identitas pengirim otomatis dari kredensial perangkat. */
    suspend fun sendMessage(
        scope: String,
        targetId: String?,   // null utk global; id lawan / "admin" utk private
        body: String,
        myDeviceId: String,
        myAndroidId: String,
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("scope", scope)
                put("sender_type", "petugas")
                put("device_id", myDeviceId)
                put("android_id", myAndroidId)
                if (scope == "private") put("target_id", targetId ?: error("target kosong"))
                put("body", body)
            }
            val json = postJson(ServerConfig.CHAT_SEND_URL, payload)
            if (!json.optBoolean("ok")) apiError(json)
            val m = json.getJSONObject("message")
            ChatMessage(
                id = m.getLong("id"),
                senderType = m.optString("sender_type"),
                senderId = m.optString("sender_id"),
                senderNama = m.optString("sender_nama"),
                targetId = if (m.isNull("target_id")) null else m.optString("target_id"),
                body = m.optString("body"),
                createdAt = m.optString("created_at"),
            )
        }
    }

    /* ================= RINGKASAN NOTIFIKASI ================= */

    /**
     * Ringkasan pesan terakhir tiap percakapan milik petugas ini.
     * Dipakai layanan latar (ChatNotifyService) untuk mendeteksi
     * pesan baru dan memunculkan notifikasi. Tidak butuh API key —
     * identitas cukup dari header perangkat.
     */
    suspend fun fetchSummary(
        myDeviceId: String,
        myAndroidId: String,
    ): Result<SummaryResult> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = get(ServerConfig.CHAT_SUMMARY_URL, myDeviceId, myAndroidId)
            val json = JSONObject(readBody(conn))
            conn.disconnect()
            if (!json.optBoolean("ok")) apiError(json)
            val me = json.getJSONObject("me")
            val arr = json.optJSONArray("convs") ?: JSONArray()
            val convs = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SummaryConv(
                    key = o.optString("key"),
                    title = o.optString("title"),
                    partner = o.optString("partner"),
                    lastId = o.optLong("last_id"),
                    lastBody = o.optString("last_body"),
                    lastAt = o.optString("last_at"),
                    lastSenderType = o.optString("last_sender_type"),
                    lastSenderId = o.optString("last_sender_id"),
                    lastSenderNama = o.optString("last_sender_nama"),
                )
            }
            SummaryResult(
                meId = me.getLong("id"),
                meNama = me.optString("nama"),
                convs = convs,
            )
        }
    }

    /* ================= PONDASI HTTP ================= */

    private fun apiError(json: JSONObject): Nothing {
        val code = json.optString("error")
        val msg = json.optString("message")
        val gabung = listOf(code, msg).filter { it.isNotEmpty() }.joinToString(" — ")
        error(if (gabung.isEmpty()) "Server menolak permintaan" else gabung)
    }

    private fun get(
        url: String,
        deviceId: String? = null,
        androidId: String? = null,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            // Header identitas — dipakai server sebagai gerbang privasi DM.
            if (!deviceId.isNullOrEmpty()) setRequestProperty("X-Device-Id", deviceId)
            if (!androidId.isNullOrEmpty()) setRequestProperty("X-Android-Id", androidId)
        }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("X-API-Key", ServerConfig.API_KEY)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Connection", "close")
        }
        conn.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = if (code in 200..299) readBody(conn) else readError(conn)
        conn.disconnect()
        if (code !in 200..299) error("HTTP $code ${text.take(160)}")
        return JSONObject(text)
    }

    private fun readBody(conn: HttpURLConnection): String =
        conn.inputStream.bufferedReader().use { it.readText() }

    private fun readError(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
}
