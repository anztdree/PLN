package com.petugas.pemasangan.ui.register

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.remote.PlnApi
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.fx.BoltMark
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GoldButton
import com.petugas.pemasangan.ui.fx.GradientHeadline
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlinx.coroutines.launch

/**
 * Layar registrasi petugas — tampil sekali di awal (atau setelah data
 * aplikasi direset). Satu kolom nama, tombol Daftar, selesai.
 *
 * Aturan yang dijaga server:
 *  - Nama harus unik antar petugas -> instruksikan memakai nama lengkap.
 *  - Nama permanen untuk perangkat ini; jika perangkat sudah dikenal
 *    (android_id), server memulihkan nama lama walau kolom diisi beda.
 */
@Composable
fun RegisterScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember { SettingsRepository(appContext) }
    val scope = rememberCoroutineScope()
    val e = LocalElectric.current

    var nama by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun daftar() {
        val bersih = nama.trim().replace(Regex("\\s+"), " ")
        if (bersih.length < 3) {
            errorText = "Tulis nama lengkap Anda (minimal 3 huruf)"
            return
        }
        if (bersih.length > 40) {
            errorText = "Nama terlalu panjang (maksimal 40 huruf)"
            return
        }
        errorText = null
        busy = true
        scope.launch {
            val deviceId = settings.deviceId()
            val androidId = settings.androidId()
            PlnApi.register(bersih, deviceId, androidId)
                .onSuccess { hasil ->
                    settings.setRegistered(hasil.petugasId, hasil.nama)
                    busy = false
                    if (hasil.restored) {
                        // Perangkat sudah dikenal pusat -> lanjutkan profil lama.
                        Toast.makeText(
                            context,
                            "Selamat datang kembali, ${hasil.nama}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onDone()
                }
                .onFailure { err ->
                    busy = false
                    val pesan = err.message.orEmpty()
                    errorText = when {
                        "nama_dipakai" in pesan ->
                            "Nama ini sudah dipakai petugas lain. Tulis nama lengkap Anda sendiri."
                        "identitas" in pesan ->
                            "Identitas perangkat gagal dikirim. Coba lagi."
                        pesan.contains("HTTP", ignoreCase = true) ||
                            pesan.contains("Unable to resolve", ignoreCase = true) ->
                            "Tidak dapat menjangkau server. Periksa kuota/internet lalu coba lagi."
                        else ->
                            "Gagal mendaftar: ${pesan.take(120)}"
                    }
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(top = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BoltMark(size = 72.dp)

            Spacer(Modifier.height(16.dp))
            GradientHeadline(
                text = "PETUGAS PLN",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
            )
            Text(
                "Pencatatan Pemasangan Listrik",
                style = MaterialTheme.typography.bodySmall,
                color = e.textMid,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(26.dp))

            GlassPanel(modifier = Modifier.fillMaxWidth().entrance(120)) {
                Text(
                    "Satu langkah sebelum mulai",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = e.textHi
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Daftar dengan nama lengkap Anda. Nama ini permanen untuk perangkat ini dan tampil di pusat kendali serta pada setiap laporan yang Anda kirim.",
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textMid
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = nama,
                    onValueChange = {
                        if (it.length <= 40) nama = it
                        errorText = null
                    },
                    label = { Text("Nama lengkap") },
                    placeholder = { Text("Contoh: Budi Santoso", color = e.textMid.copy(alpha = 0.6f)) },
                    supportingText = {
                        Text(
                            "Gunakan nama lengkap agar tidak bertabrakan dengan petugas lain.",
                            color = e.textMid.copy(alpha = 0.8f)
                        )
                    },
                    isError = errorText != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.Words
                    ),
                    leadingIcon = {
                        PlnIcon(PlnIconKind.USER, size = 19.dp, tint = e.yellow)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = e.yellow,
                        unfocusedBorderColor = e.glassBorderB,
                        cursorColor = e.yellow,
                        focusedLabelColor = e.yellow,
                        unfocusedLabelColor = e.textMid,
                        focusedTextColor = e.textHi,
                        unfocusedTextColor = e.textHi,
                        errorBorderColor = e.red,
                        errorLabelColor = e.red,
                        errorSupportingTextColor = e.red
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = e.red,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(16.dp))
                GoldButton(
                    text = if (busy) "Mendaftarkan…" else "Daftar",
                    enabled = !busy,
                    onClick = { daftar() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                    leading = {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF241A00)
                            )
                        } else {
                            PlnIcon(PlnIconKind.CHECK, size = 18.dp, tint = Color(0xFF241A00))
                        }
                    }
                )
            }

            Spacer(Modifier.height(18.dp))

            // Panel kecil: penjelasan singkat ikatan identitas
            GlassPanel(
                modifier = Modifier.fillMaxWidth().entrance(220),
                padding = PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(e.yellow.copy(alpha = 0.14f))
                            .border(1.dp, e.yellow.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlnIcon(PlnIconKind.BOLT, size = 16.dp, tint = e.yellow)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Perangkat ini akan terikat dengan nama Anda. Bila aplikasi dihapus lalu dipasang kembali, nama lama dipulihkan otomatis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textMid
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
