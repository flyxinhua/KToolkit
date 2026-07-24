package com.sanvar.ktoolkit.main

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.sanvar.ble.BleManager
import com.sanvar.ble.sanner.ScanManager
import com.sanvar.ktoolkit.permission.bleNeedPermissions

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(navHostController: NavHostController) {
    // 根据系统版本动态决定需要申请的权限
    // Android 12 (API 31) 及以上需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT
    val blePermissions = bleNeedPermissions()

    val ctx = LocalContext.current

    val permissionState = rememberMultiplePermissionsState(blePermissions)

    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                navHostController.navigate("log")
            }) { Text("Log example") }

            Spacer(Modifier.height(16.dp))

            Button(onClick = {
                // 满足权限后才打开扫描页面
                if (permissionState.allPermissionsGranted) {
                    BleManager.instance.init(ctx,true)
                    ScanManager.init(context = ctx)
                    navHostController.navigate("scanner")
                } else {
                    permissionState.launchMultiplePermissionRequest()
                }
            }) { Text("BLE example") }
        }
    }
}
