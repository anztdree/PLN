package com.petugas.pemasangan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.service.ChatPoller
import com.petugas.pemasangan.ui.chat.ChatListScreen
import com.petugas.pemasangan.ui.chat.ChatScreen
import com.petugas.pemasangan.ui.detail.DetailScreen
import com.petugas.pemasangan.ui.form.FormScreen
import com.petugas.pemasangan.ui.list.ListScreen
import com.petugas.pemasangan.ui.menu.MenuScreen
import com.petugas.pemasangan.ui.register.RegisterScreen
import com.petugas.pemasangan.ui.settings.SettingsScreen
import com.petugas.pemasangan.ui.stats.StatsScreen
import kotlinx.coroutines.flow.first
import android.net.Uri

private const val NAV_ANIM_MS = 320

@Composable
fun AppNavHost(openRoute: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()

    // Tentukan layar awal: belum terdaftar -> registrasi; sudah -> menu.
    var ready by remember { mutableStateOf(false) }
    var registered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        registered = SettingsRepository(context.applicationContext).isRegistered()
        ready = true
    }
    if (!ready) return

    // M5/M8 — pastikan pemantau pesan baru menyala begitu terdaftar.
    LaunchedEffect(registered) {
        if (registered) ChatPoller.start(context.applicationContext)
    }

    // M7 — izin notifikasi Android 13+ diminta ulang setiap aplikasi dibuka
    // selama belum dikasih (dulu di Activity.onCreate, kini setelah UI siap
    // supaya dialog pasti tampil dan pengguna paham konteksnya).
    val izinNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* hasilnya tidak perlu diproses — banner di menu mengikuti status */ }
    LaunchedEffect(registered) {
        if (registered && Build.VERSION.SDK_INT >= 33) {
            kotlinx.coroutines.delay(800) // biarkan splash selesai dulu
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) izinNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // M5 — ketukan notifikasi: buka percakapan yang bersangkutan.
    LaunchedEffect(openRoute, registered) {
        val route = openRoute ?: return@LaunchedEffect
        if (registered) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (registered) "menu" else "register",
        enterTransition = {
            slideInHorizontally(tween(NAV_ANIM_MS)) { it / 4 } +
                fadeIn(tween(NAV_ANIM_MS)) +
                scaleIn(tween(NAV_ANIM_MS), initialScale = 0.94f)
        },
        exitTransition = {
            slideOutHorizontally(tween(NAV_ANIM_MS)) { -it / 5 } +
                fadeOut(tween(NAV_ANIM_MS)) +
                scaleOut(tween(NAV_ANIM_MS), targetScale = 1.04f)
        },
        popEnterTransition = {
            slideInHorizontally(tween(NAV_ANIM_MS)) { -it / 4 } +
                fadeIn(tween(NAV_ANIM_MS)) +
                scaleIn(tween(NAV_ANIM_MS), initialScale = 0.94f)
        },
        popExitTransition = {
            slideOutHorizontally(tween(NAV_ANIM_MS)) { it / 5 } +
                fadeOut(tween(NAV_ANIM_MS)) +
                scaleOut(tween(NAV_ANIM_MS), targetScale = 1.04f)
        }
    ) {

        composable("register") {
            RegisterScreen(
                onDone = {
                    navController.navigate("menu") {
                        popUpTo("register") { inclusive = true }
                    }
                }
            )
        }

        composable("menu") {
            MenuScreen(
                onNewInstallation = { navController.navigate("form") },
                onOpenList = { navController.navigate("list") },
                // v1.18-m20 (permintaan pemilik): grafik statistik TAMPIL
                // LANGSUNG di menu (bukan tombol) — rute "stats" dipertahankan.
                onOpenSettings = { navController.navigate("settings") },
                onOpenChat = { navController.navigate("chat_list") }
            )
        }

        composable("stats") {
            StatsScreen(onBack = { navController.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable("list") {
            ListScreen(
                onBack = { navController.popBackStack() },
                onItemClick = { id -> navController.navigate("detail/$id") },
                onAddClick = { navController.navigate("form") }
            )
        }

        composable(
            route = "form?id={id}",
            arguments = listOf(navArgument("id") {
                type = NavType.LongType
                defaultValue = -1L
            })
        ) { entry ->
            val recordId = entry.arguments?.getLong("id") ?: -1L
            FormScreen(
                recordId = recordId,
                onDone = { navController.popBackStack() }
            )
        }

        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val recordId = entry.arguments?.getLong("id") ?: 0L
            DetailScreen(
                recordId = recordId,
                onBack = { navController.popBackStack() }
            )
        }

        /* ================= OBROLAN (M4) ================= */

        composable("chat_list") {
            var identity by remember { mutableStateOf<Pair<Long, String>?>(null) }
            LaunchedEffect(Unit) {
                val s = SettingsRepository(context.applicationContext)
                val id = s.petugasIdFlow.first().toLongOrNull() ?: 0L
                identity = id to s.officerNameFlow.first()
            }
            identity?.let { (id, nama) ->
                ChatListScreen(
                    myId = id,
                    myName = nama,
                    onBack = { navController.popBackStack() },
                    onOpenGlobal = { navController.navigate("chat/global") },
                    onOpenAdmin = { navController.navigate("chat/admin") },
                    onOpenPetugas = { pid, pnama ->
                        navController.navigate("chat/private/$pid?nama=${Uri.encode(pnama)}")
                    }
                )
            }
        }

        composable("chat/global") {
            val myId = remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                myId.value = SettingsRepository(context.applicationContext)
                    .petugasIdFlow.first().toLongOrNull() ?: 0L
            }
            ChatScreen(
                title = "Diskusi Petugas",
                scope = "global",
                targetId = null,
                myId = myId.value,
                showNames = true,
                onBack = { navController.popBackStack() }
            )
        }

        composable("chat/admin") {
            val myId = remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                myId.value = SettingsRepository(context.applicationContext)
                    .petugasIdFlow.first().toLongOrNull() ?: 0L
            }
            ChatScreen(
                title = "Pusat",
                scope = "private",
                targetId = "admin",
                myId = myId.value,
                showNames = false,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "chat/private/{id}?nama={nama}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("nama") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val lawanId = entry.arguments?.getLong("id") ?: 0L
            val lawanNama = Uri.decode(entry.arguments?.getString("nama") ?: "")
            val myId = remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                myId.value = SettingsRepository(context.applicationContext)
                    .petugasIdFlow.first().toLongOrNull() ?: 0L
            }
            ChatScreen(
                title = lawanNama.ifBlank { "Pesan Pribadi" },
                scope = "private",
                targetId = lawanId.toString(),
                myId = myId.value,
                showNames = false,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
