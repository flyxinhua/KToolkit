# KBLE

[![Maven Central](https://img.shields.io/maven-central/v/io.github.flyxinhua/kble.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.flyxinhua/kble)

KBLE 是一个专为 Android 打造的轻量级、高性能的蓝牙低功耗 (BLE) 框架。它提供了一种设备中心的连接管理模型，集成了自动重连、连接守护、生命周期感知以及蓝牙状态监控等高级特性，旨在帮助开发者以简洁的方式实现稳定可靠的 BLE 通信。

## 核心特性

- **设备中心连接管理**: 每个 `BleDevice` 实例独立管理一个设备的连接、服务发现和数据操作。
- **智能扫描**: 内置 `ScanManager` 采用 Duty Cycle (30s 扫描 / 1s 暂停) 机制，优化扫描性能和电量消耗。
- **自动连接与重连**: 内置强大的重连机制和连接守护 (Guard) 模式，确保连接的持久性。
- **生命周期感知**: 自动监听应用前后台切换，智能管理连接状态。
- **蓝牙状态监控**: 自动监听系统蓝牙开关状态，并通知所有关联设备。
- **简洁的 API**: 通过 `BleManager` 进行全局初始化和设备实例获取，操作直观。
- **高可配置性**: 通过 `BleConfig` 灵活配置连接超时、重连间隔、MTU 协商等参数。
- **零第三方依赖**: 核心模块轻量、纯净，减少依赖冲突。

## 安装

在你的 `build.gradle.kts` (或 `build.gradle`) 文件中添加依赖：

```kotlin
dependencies {
    implementation("io.github.flyxinhua:kble:1.0.0")
}
```
*(请将 `1.0.0` 替换为最新的版本号)*

## 基础用法

### 1. 初始化 KBLE

在你的 `Application` 类的 `onCreate` 方法中完成初始化。

```kotlin
import com.sanvar.ble.BleManager
import android.app.Application

class YourApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 建议在 Application 启动时初始化
        // 第二个参数 enableLog 默认为 true，用于开启内部日志
        BleManager.instance.init(this, enableLog = BuildConfig.DEBUG)
    }
}
```

### 2. 扫描设备

使用 `ScanManager.getInstance()` 来启动和停止扫描。扫描会以 Duty Cycle 模式运行，直到所有监听器被移除。

```kotlin
import com.sanvar.ble.sanner.ScanManager
import com.sanvar.ble.sanner.ScanManager.ScanCallback
import android.bluetooth.BluetoothDevice
import com.sanvar.log.KLog

val scanCallback = object : ScanCallback {
    override fun onScanDevice(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
        // 处理扫描到的设备信息
        KLog.d { "Found device: ${device?.name} at RSSI: $rssi" }
    }
}

// 启动扫描（如果扫描未运行，则启动）
ScanManager.getInstance().startScan(scanCallback)

// ...

// 停止扫描（只有当所有监听器都停止后，扫描才会真正停止）
ScanManager.getInstance().stopScan(scanCallback)
```

### 3. 获取设备并配置

通过 `BleConfig` 指定设备的 MAC 地址和连接配置，然后从 `BleManager` 获取或创建一个 `BleDevice` 实例。

```kotlin
import com.sanvar.ble.BleConfig

val macAddress = "00:1A:7D:XX:XX:XX"

val config = BleConfig.builder()
    .macAddress(macAddress)
    .enableGuard(true) // 开启连接守护，应用回到前台时自动尝试恢复连接
    .maxReconnectAttempts(-1) // 无限重试
    .preferredMtu(256) // 设置期望的 MTU
    .build()

val bleDevice = BleManager.instance.getOrPutDevice(config)
```

### 4. 连接与操作 (使用 BleCallback)

`BleDevice` 的所有异步操作和状态变更，如连接、MTU 变化、数据通知和读取完成，都通过 `BleCallback` 统一回调。

```kotlin
import com.sanvar.ble.BleCallback
import com.sanvar.ble.ConnectionState
import android.bluetooth.BluetoothDevice
import com.sanvar.log.KLog
import java.util.UUID

// 1. 定义回调对象
val bleCallback = object : BleCallback {
    override fun onConnectionStateChanged(state: ConnectionState, device: BluetoothDevice?) {
        when (state) {
            ConnectionState.READY -> KLog.d { "设备已连接成功并初始化完成" }
            ConnectionState.DISCONNECTED -> KLog.d { "设备已断开连接" }
            else -> KLog.i { "连接状态变化: $state" }
        }
    }

    override fun onMtuChanged(mtu: Int) {
        KLog.i { "MTU 已协商为: $mtu" }
    }

    // 处理通知或指示收到的数据
    override fun onNotificationReceived(uuid: UUID, data: ByteArray) {
        // ... 处理接收到的数据
        KLog.d { "收到通知/指示，UUID: $uuid, 数据长度: ${data.size}" }
    }

    // 处理特征读取操作完成的结果
    override fun onReadComplete(uuid: UUID, data: ByteArray?, success: Boolean) {
        if (success) {
            // data?.let { /* 处理数据 */ }
            KLog.d { "特征读取成功，UUID: $uuid, 数据长度: ${data?.size ?: 0}" }
        } else {
            KLog.e { "特征读取失败，UUID: $uuid" }
        }
    }
}

// 2. 注册回调并连接
bleDevice.registerCallback(bleCallback)
bleDevice.connect()

// 3. 执行操作（例如异步读取特征值）
// bleDevice.read(characteristicUUID) 

// 4. 释放资源时注销回调并断开连接
bleDevice.unregisterCallback(bleCallback)
bleDevice.disconnect()
BleManager.instance.removeDevice(macAddress)
```

## 权限要求

KBLE 依赖于 Android 的标准蓝牙权限。在 `AndroidManifest.xml` 中需要声明以下权限（具体要求取决于 Android 版本和功能）：

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /> <!-- 对于 Android 11 及以下版本进行扫描是必需的 -->
```

## 架构思想: 设备中心模型

KBLE 采用设备中心 (Device-Centric) 模型。每个蓝牙外设都被抽象为一个 `BleDevice` 对象，所有的连接、数据操作和状态监听都绑定在这个对象上。这使得管理多个设备连接变得非常清晰和简单。

## 许可证

    Copyright 2024 sanvar

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.