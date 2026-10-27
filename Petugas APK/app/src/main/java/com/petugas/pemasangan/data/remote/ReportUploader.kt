package com.petugas.pemasangan.data.remote

import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.util.ImageCompressor
import com.petugas.pemasangan.util.PhotoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Kabel penghubung form petugas -> server pusat.
 *
 * Mengirim satu laporan + semua fotonya dalam SATU request multipart
 * ke POST {ServerConfig.REPORTS_URL} dengan header "X-API-Key".
 *
 * Karakteristik:
 *  - Tanpa library tambahan (pakai HttpURLConnection bawaan Android) -> APK tetap ramping.
 *  - Foto dikompresi dulu lewat [ImageCompressor] (1080 px, ±150-300 KB/foto).
 *  - client_ref unik per record lokal ("L<id>-<createdAt>") supaya server
 *    bisa mendeteksi kiriman ulang data yang sama di kemudian hari.
 *  - Gagal jaringan TIDAK membuang data: laporan tetap tersimpan lokal,
 *    petugas cukup membuka lagi datanya dan menekan Kirim.
 */
object ReportUploader {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Kirim satu laporan ke server pusat. Sukses = HTTP 2xx. */
    suspend fun kirim(entity: InstallationEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 1. Siapkan foto terkompresi (foto yang gagal dibaca diabaikan).
                val fotos = PhotoCodec.decode(entity.photos)
                    .mapNotNull { path -> ImageCompressor.compress(path) }
                if (fotos.isEmpty()) error("Tidak ada foto yang bisa dikompres")

                // 2. Buka koneksi ke server pusat.
                val conn = (URL(ServerConfig.REPORTS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    setRequestProperty("X-API-Key", ServerConfig.API_KEY)
                    setRequestProperty("Connection", "close")
                }

                val boundary = "----PLN" + System.currentTimeMillis().toString(16)
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                // 3. Rakit badan multipart: field data + foto.
                DataOutputStream(conn.outputStream).use { out ->
                    field(out, boundary, "client_ref", "L" + entity.id + "-" + entity.createdAt)
                    field(out, boundary, "nama_pelanggan", entity.customerName)
                    field(out, boundary, "id_pelanggan", entity.customerId)
                    field(out, boundary, "alamat", entity.address)
                    field(out, boundary, "petugas", entity.officerName)
                    field(out, boundary, "status", entity.status)
                    field(out, boundary, "catatan", entity.notes)
                    field(out, boundary, "latitude", String.format(Locale.US, "%.6f", entity.latitude))
                    field(out, boundary, "longitude", String.format(Locale.US, "%.6f", entity.longitude))
                    field(out, boundary, "created_at", isoFormat.format(Date(entity.createdAt)))

                    fotos.forEachIndexed { i, bytes ->
                        val head = buildString {
                            append("--").append(boundary).append("\r\n")
                            append("Content-Disposition: form-data; name=\"photos\"; filename=\"foto_")
                            append(i + 1).append(".jpg\"\r\n")
                            append("Content-Type: image/jpeg\r\n\r\n")
                        }.toByteArray(Charsets.US_ASCII)
                        out.write(head)
                        out.write(bytes)
                        out.writeBytes("\r\n")
                    }

                    out.writeBytes("--" + boundary + "--\r\n")
                    out.flush()
                }

                // 4. Baca hasil; HTTP 2xx dianggap sukses.
                val code = conn.responseCode
                if (code !in 200..299) {
                    val pesan = runCatching { conn.errorStream?.bufferedReader()?.readText() }
                        .getOrNull()?.take(200).orEmpty()
                    conn.disconnect()
                    error("Server menjawab HTTP $code $pesan")
                }
                conn.disconnect()
            }
        }

    /** Tulis satu field teks sebagai part multipart (aman UTF-8). */
    private fun field(out: DataOutputStream, boundary: String, name: String, value: String) {
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        out.write(head)
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }
}
