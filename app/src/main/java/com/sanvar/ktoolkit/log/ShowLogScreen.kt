package com.sanvar.ktoolkit.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.sanvar.ktoolkit.weiget.CenterTopBar
import com.sanvar.log.KLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowLogScreen(navHostController: NavHostController) {
    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        CenterTopBar(titleText = "ShowLogScreen", onBack = {
            navHostController.popBackStack()
        })
    }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text("KLog Demo")

            Button(
                onClick = { KLog.d { "Log.d ${System.currentTimeMillis()}" } },
                modifier = Modifier.padding(8.dp)
            ) { Text("Log.d") }
            Button(
                onClick = { KLog.i { "Log.i ${System.currentTimeMillis()}" } },
                modifier = Modifier.padding(8.dp)
            ) { Text("Log.i") }
            Button(
                onClick = { KLog.w { "Log.w ${System.currentTimeMillis()}" } },
                modifier = Modifier.padding(8.dp)
            ) { Text("Log.w") }
            Button(
                onClick = { KLog.e { "Log.e ${System.currentTimeMillis()}" } },
                modifier = Modifier.padding(8.dp)
            ) { Text("Log.e") }


            Button(
                onClick = { KLog.e(throwable = Exception(" test throwable")) { "Log.e" } },
                modifier = Modifier.padding(8.dp)
            ) { Text("throwable") }

        }
    }
}