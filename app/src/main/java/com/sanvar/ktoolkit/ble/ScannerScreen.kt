package com.sanvar.ktoolkit.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.sanvar.ble.BleManager
import com.sanvar.ble.sanner.ScanManager
import com.sanvar.ktoolkit.permission.bleNeedPermissions
import com.sanvar.ktoolkit.weiget.CenterTopBar
import com.sanvar.log.KLog


@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(navHostController: NavHostController) {

    var hasPermission by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current

    // 搜索关键词
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 缓存扫到的设备列表
    val scannerResult = remember { mutableStateListOf<BluetoothDevice>() }

    // 过滤后的设备列表
    val filteredDevices = remember(searchQuery, scannerResult) {
        if (searchQuery.isBlank()) {
            scannerResult
        } else {
            val query = searchQuery.lowercase()
            scannerResult.filter { device ->
                // 搜索设备名称或 MAC 地址
                val nameMatch =
                    !device.name.isNullOrBlank() && device.name.lowercase().contains(query)
                val addressMatch = device.address.lowercase().contains(query)
                nameMatch || addressMatch
            }
        }
    }

    val scannerCallBack = object : ScanManager.ScanCallbackWrapper {
        @SuppressLint("MissingPermission")
        override fun onScanResult(result: ScanResult) {
            if (!result.device.name.isNullOrBlank()) {
                // 加入列表
                val isExists = scannerResult.any { exitDev ->
                    exitDev.address == result.device.address
                }
                if (!isExists) {
                    scannerResult.add(result.device)
                    KLog.i { "Found BLE device: ${result.device.name} @ ${result.device.address}" }
                }
            }
        }
    }


    ///  1. 申请扫描权限 然后 扫描设备
    val permissionState = rememberMultiplePermissionsState(bleNeedPermissions()) { result ->
        hasPermission = result.all { it.value }
        KLog.i { "是否有权限: $hasPermission" }
        if (!hasPermission) {
            Toast.makeText(ctx, "请授予蓝牙相关权限和位置权限", Toast.LENGTH_SHORT).show()
        } else {
            // 进行扫描
            ScanManager.init(context = ctx)
            ScanManager.startScan(scannerCallBack)
        }
    }

    LaunchedEffect(Unit) {
        // 初始化蓝牙相关的API
        BleManager.instance.init(ctx)
        // 请求权限
        permissionState.launchMultiplePermissionRequest()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (hasPermission) {
                ScanManager.stopScan(scannerCallBack)
            }
        }
    }


    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        CenterTopBar(titleText = "Scanner", onBack = {
            navHostController.popBackStack()
        })
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // 搜索框
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { keyboardController?.hide() },
                placeholder = "搜索设备名称或 MAC 地址"
            )

            Spacer(Modifier.height(16.dp))

            // 显示结果数量
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "搜索结果: ${filteredDevices.size} 个设备",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {

                itemsIndexed(
                    items = filteredDevices,
                    // 使用设备的地址作为 key
                    key = { index, device -> device.address }
                ) { index, device ->
                    // 这里的 Composable 代表列表中的一项
                    DeviceItem(
                        device = device,
                        index,
                        onClick = {
                            // 假设点击后可能会导航或开始连接，这不会影响列表的添加操作
                            navHostController.navigate("ble/${device.address}")
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}


/**
 * 搜索框组件
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "搜索",
                tint = Color.Gray
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Text(
                    text = "清除",
                    fontSize = 14.sp,
                    color = Color.Blue,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable { onQueryChange("") }
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = { onSearch() }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.LightGray.copy(0.3f),
            unfocusedContainerColor = Color.LightGray.copy(0.3f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceItem(device: BluetoothDevice, index: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (index % 2 != 0) Color.LightGray.copy(0.1f) else Color.White)
            .padding(16.dp)
            .clickable(onClick = onClick)
    ) {
        Text("${device.name}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("${device.address}", fontSize = 16.sp)
    }
}
