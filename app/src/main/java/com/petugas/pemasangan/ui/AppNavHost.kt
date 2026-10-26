package com.petugas.pemasangan.ui

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

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "menu") {

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
