package com.petugas.pemasangan.ui.chat

import com.petugas.pemasangan.data.remote.PlnApi

/**
 * Jembatan kecil antara layar obrolan, layanan notifikasi, dan Activity.
 * Semua state bersifat sementara (in-memory, ikut proses aplikasi).
 */
object ChatShared {

    /**
     * Kunci percakapan yang SEDANG terbuka di layar. Layanan notifikasi
     * melewati percakapan ini (pengguna sedang membacanya langsung).
     */
    @Volatile
    var activeConvKey: String? = null

    /**
     * Rute navigasi yang menunggu diproses (dari ketukan notifikasi).
     * Format sama dengan rute AppNavHost, mis. "chat/global".
     */
    @Volatile
    var pendingRoute: String? = null

    /**
     * Cache daftar petugas (hasil GET /api/petugas) — dipakai bersama
     * ChatListScreen & ChatScreen supaya layar terbuka instan tanpa
     * menunggu jaringan, lalu diperbarui di belakang.
     */
    @Volatile
    var petugas: List<PlnApi.PetugasItem>? = null

    /**
     * Cache pesan per percakapan — kunci = kunci percakapan
     * ("global" / "9|admin" / "3|9"). Membuka obrolan yang sama untuk
     * kedua kalinya langsung menampilkan isi lama (terasa cepat).
     */
    private val messageCache = HashMap<String, List<PlnApi.ChatMessage>>()

    @Synchronized
    fun messages(convKey: String): List<PlnApi.ChatMessage>? = messageCache[convKey]

    @Synchronized
    fun putMessages(convKey: String, msgs: List<PlnApi.ChatMessage>) {
        messageCache[convKey] = msgs
        // Jaga ukuran cache tetap kecil (maks 12 percakapan terakhir dipakai).
        if (messageCache.size > 12) {
            val tertua = messageCache.keys.first()
            messageCache.remove(tertua)
        }
    }

    /**
     * Kunci percakapan — HARUS sama dengan aturan server:
     * "global", atau "a|b" dengan angka terurut menaik ("3|10",
     * bukan "10|3"); "admin" bukan angka sehingga selalu di belakang.
     */
    fun convKeyOf(scope: String, myId: Long, targetId: String?): String {
        if (scope != "private") return "global"
        val a = myId.toString()
        val b = targetId.orEmpty()
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        return if (na != null && nb != null) {
            if (na <= nb) "$a|$b" else "$b|$a"
        } else {
            if (a <= b) "$a|$b" else "$b|$a"
        }
    }
}
