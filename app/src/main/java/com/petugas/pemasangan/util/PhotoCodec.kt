package com.petugas.pemasangan.util

import org.json.JSONArray

/**
 * Util konversi daftar path foto <-> JSON string untuk disimpan di Room.
 */
object PhotoCodec {

    fun encode(paths: List<String>): String = JSONArray(paths).toString()

    fun decode(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
}
