package com.petugas.pemasangan.ui

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
fun AppNavHost() {
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
                onOpenStats = { navController.navigate("stats") },
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
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate("form?id=$id") }
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
                title = "Obrolan Umum",
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
