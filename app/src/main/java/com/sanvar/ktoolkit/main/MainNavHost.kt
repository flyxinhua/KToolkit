package com.sanvar.ktoolkit.main

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sanvar.ktoolkit.ble.BLEScreen
import com.sanvar.ktoolkit.ble.ScannerScreen
import com.sanvar.ktoolkit.log.ShowLogScreen

@Composable
fun MainNavHost(navController: NavHostController) {

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(navController)
        }
        composable("log") {
            ShowLogScreen(navController)
        }

        composable(
            "ble/{mac}",
            arguments = listOf(navArgument("mac") { type = NavType.StringType })
        ) {
            val mac = it.arguments?.getString("mac") ?: ""
            BLEScreen(navController, mac)
        }

        composable("scanner") {
            ScannerScreen(navController)
        }
    }
}