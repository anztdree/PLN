package com.pln.admin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notifikasi status bar — pesan baru dari petugas & laporan lapangan baru. */
object Notify {
    const val CHANNEL_ID = "kabar-admin"
    const val ID_CHAT = 101
    const val ID_REPORT = 102

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        nm.createNotificationChannel(ch)
    }

    private fun pendingOpen(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun show(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingOpen(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            /* izin notifikasi dimatikan pengguna — abaikan */
        }
    }

    fun chat(context: Context, total: Int) {
        show(
            context,
            ID_CHAT,
            "Pesan baru dari petugas",
            "Ada $total pesan menunggu dibaca di obrolan.",
        )
    }

    fun report(context: Context, nama: String, petugas: String) {
        show(
            context,
            ID_REPORT,
            "Laporan lapangan baru",
            "$nama mengirim laporan — petugas $petugas.",
        )
    }
}
