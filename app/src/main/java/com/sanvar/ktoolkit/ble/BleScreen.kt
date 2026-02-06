package com.sanvar.ktoolkit.ble

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.sanvar.ble.BleCallback
import com.sanvar.ble.BleConfig
import com.sanvar.ble.BleDevice
import com.sanvar.ble.BleManager
import com.sanvar.ble.ConnectionState
import com.sanvar.ktoolkit.weiget.CenterTopBar
import com.sanvar.log.KLog
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BLEScreen(navHostController: NavHostController, macAddress: String) {

    var bleDevice by remember {
        val config = BleConfig(macAddress)
        val dev = BleManager.instance.getOrPutDevice(config)
        mutableStateOf<BleDevice>(dev)
    }

    var bleMtu by remember { mutableStateOf(23) }


    var baseInfo by remember { mutableStateOf(BleBaseInfo(mac = macAddress, name = macAddress)) }

    fun readBaseInfo() {
        bleDevice.read(BleUUID.FIRM_VERSION)
        bleDevice.read(BleUUID.SOFT_VERSION)
        bleDevice.read(BleUUID.HARD_VERSION)
        bleDevice.read(BleUUID.MODEL)
        bleDevice.read(BleUUID.SYSID)
        bleDevice.read(BleUUID.MANU)
        bleDevice.read(BleUUID.SERIAL)
        bleDevice.read(BleUUID.BATTERY)
    }


    val bleCallback = object : BleCallback {
        override fun onConnectionStateChanged(state: ConnectionState, device: BluetoothDevice?) {
            baseInfo = baseInfo.copy(
                name = device?.name ?: "",
                isConnected = state == ConnectionState.READY  // 此处用准备好了，作为连接成功。
            )

            if (state == ConnectionState.READY) {
                // 读取相关内容
                readBaseInfo()
            }
        }

        override fun onMtuChanged(mtu: Int) {
            bleMtu = mtu
        }

        override fun onNotificationReceived(uuid: UUID, data: ByteArray) {
            handleCharacteristicRead(uuid, data)
        }

        override fun onReadComplete(uuid: UUID, data: ByteArray?, success: Boolean) {
            handleCharacteristicRead(uuid, data ?: byteArrayOf())
        }


        private fun handleCharacteristicRead(uuid: UUID, data: ByteArray) {
            val feature = BleUUID.identify(uuid)

            when (feature) {
                is BleUUID.BleFeature.Battery -> {
                    baseInfo = baseInfo.copy(battery = data[0].toInt())
                    KLog.i { "battery: ${data[0]}" }

                }

                is BleUUID.BleFeature.FirmwareVersion -> {
                    baseInfo = baseInfo.copy(firmwareVersion = String(data))
                    KLog.i { "firmwareVersion:${String(data)}" }
                }

                is BleUUID.BleFeature.SoftwareVersion -> {
                    baseInfo = baseInfo.copy(softVersion = String(data))
                    KLog.i { "softVersion:${String(data)}" }
                }

                is BleUUID.BleFeature.HardwareVersion -> {
                    baseInfo = baseInfo.copy(hardwareVersion = String(data))
                    KLog.i { "hardwareVersion:${String(data)}" }
                }

                is BleUUID.BleFeature.SystemId -> {
                    baseInfo = baseInfo.copy(sysId = String(data))
                    KLog.i { "sysId:${String(data)}" }
                }

                is BleUUID.BleFeature.ModelNumber -> {
                    baseInfo = baseInfo.copy(model = String(data))
                    KLog.i { "model:${String(data)}" }
                }

                is BleUUID.BleFeature.SerialNumber -> {
                    baseInfo = baseInfo.copy(serial = String(data))
                    KLog.i { "serial:${String(data)}" }
                }

                is BleUUID.BleFeature.ManufacturerName -> {
                    baseInfo = baseInfo.copy(manufacturer = String(data))
                    KLog.i { "manufacturer:${String(data)}" }
                }

                else -> {
                    when (uuid) {
                        BleUUID.HEART_RATE_MEASUREMENT -> {
                            val heartRateData = parseHeartRateMeasurement(data)
                            KLog.i { "heartRateData: $heartRateData" }
                        }

                        else -> {
                            KLog.i { "notify uuid: $uuid  , data:${String(data)}" }
                        }
                    }

                }
            }
        }

    }

    LaunchedEffect(Unit) {
        bleDevice.registerCallback(bleCallback)
        bleDevice.connect()
    }

    DisposableEffect(Unit) {
        onDispose {
            bleDevice.unregisterCallback(bleCallback)
            bleDevice.disconnect()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        CenterTopBar(titleText = baseInfo.name, onBack = {
            navHostController.popBackStack()
        })
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StateItem("name", baseInfo.name)
            StateItem("mac", baseInfo.mac)
            StateItem("isConnected", "${baseInfo.isConnected}")
            StateItem("softwareVersion", baseInfo.softVersion)
            StateItem("hardwareVersion", baseInfo.hardwareVersion)
            StateItem("firmwareVersion", baseInfo.firmwareVersion)
            StateItem("model", baseInfo.model)
            StateItem("serial", baseInfo.serial)
            StateItem("sysId", baseInfo.sysId)
            StateItem("manufacturer", baseInfo.manufacturer)
            StateItem("battery", "${baseInfo.battery}")
            StateItem("bleMtu", "$bleMtu")
        }
    }
}

internal data class BleBaseInfo(
    val name: String = "",
    val mac: String = "",
    val isConnected: Boolean = false,
    val battery: Int = 0,
    val firmwareVersion: String = "",
    val hardwareVersion: String = "",
    val manufacturer: String = "",
    val serial: String = "",
    val softVersion: String = "",
    val sysId: String = "",
    val model: String = "",
)


data class HeartRateData(
    val heartRateBpm: Int,
    val isHeartRateUint16: Boolean,
    val sensorContactSupported: Boolean,
    val sensorContactDetected: Boolean,
    val energyExpendedKj: Int? = null,
    val rrIntervals: List<Double> = emptyList()
)


fun parseHeartRateMeasurement(data: ByteArray): HeartRateData? {
    if (data.isEmpty()) return null

    // 1. 解析标志位
    val flags = data[0].toInt() and 0xFF
    var offset = 1

    // Bit 0: 心率值格式 (0=UINT8, 1=UINT16)
    val isUint16 = (flags and 0x01) != 0

    // Bit 1 & 2: 传感器接触状态
    val contactStatus = (flags shr 1) and 0x03
    val sensorContactSupported = contactStatus != 0 // 00=Not Supported
    val sensorContactDetected = contactStatus == 3  // 11=Detected

    // Bit 3: 能量消耗状态
    val hasEnergyExpended = (flags and 0x08) != 0

    // Bit 4: RR 间隔状态
    val hasRrInterval = (flags and 0x10) != 0

    // 2. 提取心率值
    val heartRateBpm: Int
    if (isUint16) {
        // UINT16 (2字节)
        if (data.size < offset + 2) return null
        heartRateBpm =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
    } else {
        // UINT8 (1字节)
        if (data.size < offset + 1) return null
        heartRateBpm = data[offset].toInt() and 0xFF
        offset += 1
    }

    // 3. 提取能量消耗
    var energyExpended: Int? = null
    if (hasEnergyExpended) {
        // UINT16 (2字节)
        if (data.size < offset + 2) return null
        energyExpended =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
    }

    // 4. 提取 RR 间隔
    val rrIntervals = mutableListOf<Double>()
    if (hasRrInterval) {
        // RR 间隔是 UINT16 (2字节) 列表，直到数据结束
        while (data.size >= offset + 2) {
            val rrValue =
                (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

            // 换算: RR 间隔单位是 1/1024秒。 (1000 / 1024) * rrValue
            // 约等于 rrValue * 0.9765625 毫秒
            val rrInMs = rrValue * (1000.0 / 1024.0)
            rrIntervals.add(rrInMs)
            offset += 2
        }
    }

    return HeartRateData(
        heartRateBpm = heartRateBpm,
        isHeartRateUint16 = isUint16,
        sensorContactSupported = sensorContactSupported,
        sensorContactDetected = sensorContactDetected,
        energyExpendedKj = energyExpended,
        rrIntervals = rrIntervals
    )
}

@Composable
private fun StateItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, fontSize = 14.sp)
    }
}