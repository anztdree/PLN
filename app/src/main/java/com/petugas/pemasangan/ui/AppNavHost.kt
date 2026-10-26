package com.petugas.pemasangan.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.petugas.pemasangan.ui.detail.DetailScreen
import com.petugas.pemasangan.ui.form.FormScreen
import com.petugas.pemasangan.ui.list.ListScreen
import com.petugas.pemasangan.ui.menu.MenuScreen

private const val NAV_ANIM_MS = 320

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "menu",
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

        composable("menu") {
            MenuScreen(
                onNewInstallation = { navController.navigate("form") },
                onOpenList = { navController.navigate("list") }
            )
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
    }
}
