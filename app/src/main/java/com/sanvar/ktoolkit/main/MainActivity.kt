package com.sanvar.ktoolkit.main

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sanvar.log.KLog
import com.sanvar.log.LogCatPrinter
import com.sanvar.log.SmartFilePrinter
import com.sanvar.log.UDPLogPrinter
import com.sanvar.log.WarpLogPrinter
import com.sanvar.ktoolkit.ui.theme.KToolKitTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupLog(this)
        setContent {
            val navController = rememberNavController()
            KToolKitTheme(darkTheme = false) {
                MainNavHost(navController)
            }
        }
    }
}


private fun setupLog(ctx: Context) {
    val warpLogPrinter = WarpLogPrinter()
    warpLogPrinter.addLogPrinter(LogCatPrinter("KToolKit"))
    warpLogPrinter.addLogPrinter(UDPLogPrinter(9999))

    val dir = File(ctx.filesDir, "KToolKit")
    warpLogPrinter.addLogPrinter(SmartFilePrinter(dir.absolutePath))
    KLog.setup(true, output = warpLogPrinter)
}



