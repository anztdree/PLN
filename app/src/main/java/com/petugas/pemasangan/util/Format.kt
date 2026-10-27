package com.petugas.pemasangan.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDate(millis: Long): String =
    SimpleDateFormat("dd MMMM yyyy", Locale("id", "ID")).format(Date(millis))

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(millis))
