package com.petugas.pemasangan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.petugas.pemasangan.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Menyalakan kembali layanan pemantau pesan setelah ponsel dinyalakan,
 * TANPA perlu membuka aplikasi dulu — supaya notifikasi pesan baru
 * tetap hidup sejak pagi.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val registered = SettingsRepository(context.applicationContext).isRegistered()
                if (registered) ChatNotifyService.start(context.applicationContext)
            } catch (_: Exception) {
                // diamkan — aplikasi berikutnya yang dibuka akan menyalakan layanan
            } finally {
                pending.finish()
            }
        }
    }
}
