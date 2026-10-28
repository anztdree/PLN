package com.petugas.pemasangan.data.remote

/**
 * Konfigurasi server pusat — "kabel utama" yang menghubungkan APK ke Website Admin.
 *
 * Layanan yang tersedia (M4):
 *  - POST {REPORTS_URL}     : kirim laporan (multipart: data + foto terkompresi)
 *  - POST {REGISTER_URL}    : daftar petugas sekali (nama permanen per perangkat)
 *  - POST {HEARTBEAT_URL}   : tandai hadir selama aplikasi terbuka (status online admin)
 *  - GET  {PETUGAS_URL}     : daftar petugas terdaftar (+ status online)
 *  - POST {CHAT_SEND_URL}   : kirim pesan obrolan (teks saja)
 *  - GET  {CHAT_MESSAGES_URL}: ambil pesan obrolan (polling)
 *
 * Keamanan: API_KEY statis dikirim sebagai header "X-API-Key" pada setiap
 * permintaan tulis. Identitas petugas dibuktikan lewat device_id/android_id —
 * server menolak pengirim yang tidak bisa membuktikan perangkatnya.
 */
object ServerConfig {

    /** Alamat server pusat produksi (Cloudflare Workers — gratis tier). */
    const val BASE_URL = "https://pln-admin.andri-12744.workers.dev"

    /** Kunci statis anti-spam. Di-enforce penuh oleh server. */
    const val API_KEY = "PLN-KLTEN-2026-KABEL-9f4a2c"

    /** Endpoint penerima laporan. */
    const val ENDPOINT_REPORTS = "/api/reports"

    /** Endpoint identitas & kehadiran. */
    const val ENDPOINT_REGISTER = "/api/petugas/register"
    const val ENDPOINT_HEARTBEAT = "/api/petugas/heartbeat"
    const val ENDPOINT_PETUGAS = "/api/petugas"

    /** Endpoint obrolan (teks saja). */
    const val ENDPOINT_CHAT_SEND = "/api/chat/send"
    const val ENDPOINT_CHAT_MESSAGES = "/api/chat/messages"

    /** Ringkasan percakapan utk notifikasi (layanan latar). */
    const val ENDPOINT_CHAT_SUMMARY = "/api/chat/summary"

    /** Catatan pusat atas laporan milik petugas (v1.16-m17). */
    const val ENDPOINT_REPORTS_CATATAN = "/api/reports/catatan-pusat"

    /** URL lengkap siap pakai. */
    val REPORTS_URL: String get() = "$BASE_URL$ENDPOINT_REPORTS"
    val REGISTER_URL: String get() = "$BASE_URL$ENDPOINT_REGISTER"
    val HEARTBEAT_URL: String get() = "$BASE_URL$ENDPOINT_HEARTBEAT"
    val PETUGAS_URL: String get() = "$BASE_URL$ENDPOINT_PETUGAS"
    val CHAT_SEND_URL: String get() = "$BASE_URL$ENDPOINT_CHAT_SEND"
    val CHAT_MESSAGES_URL: String get() = "$BASE_URL$ENDPOINT_CHAT_MESSAGES"
    val CHAT_SUMMARY_URL: String get() = "$BASE_URL$ENDPOINT_CHAT_SUMMARY"
    val REPORTS_CATATAN_URL: String get() = "$BASE_URL$ENDPOINT_REPORTS_CATATAN"
}
