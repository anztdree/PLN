package com.petugas.pemasangan.data.remote

/**
 * Konfigurasi server pusat — "kabel utama" yang menghubungkan APK ke Website Admin.
 *
 * Semua laporan yang petugas kirim dari form akan menuju ke sini:
 *   POST {BASE_URL}{ENDPOINT_REPORTS}  (multipart: data + foto terkompresi)
 *
 * Keamanan: API_KEY statis dikirim sebagai header "X-API-Key" pada setiap
 * permintaan supaya sembarang orang tidak bisa spam ke server.
 * (Bukan login — hanya kunci rahasia aplikasi, bisa diganti sewaktu-waktu.)
 */
object ServerConfig {

    /** Alamat server pusat produksi (Cloudflare Workers — gratis tier). */
    const val BASE_URL = "https://pln-admin.andri-12744.workers.dev"

    /** Kunci statis anti-spam. Di-enforce penuh oleh server pada tahap M2. */
    const val API_KEY = "PLN-KLTEN-2026-KABEL-9f4a2c"

    /** Endpoint penerima laporan. */
    const val ENDPOINT_REPORTS = "/api/reports"

    /** URL lengkap endpoint laporan. */
    val REPORTS_URL: String get() = "$BASE_URL$ENDPOINT_REPORTS"
}
