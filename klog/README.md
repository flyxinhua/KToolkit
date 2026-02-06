# KLog

[![Maven Central](https://img.shields.io/maven-central/v/io.github.flyxinhua/klog.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.flyxinhua/klog)

KLog 是一个为 Android 设计的轻量、强大且高度可扩展的日志框架。它拥有简洁的 API，并采用高性能的懒加载设计，同时通过可插拔的打印器（`LogPrinter`）架构，支持将日志同时输出到 Logcat、文件、网络或任何你想要的目标。

## 核心特性

- **简洁的静态 API**: 像使用原生 `Log` 一样通过 `KLog.d(...)`, `KLog.e(...)` 轻松记录日志。
- **高性能设计**: 日志消息的构建采用**懒加载**模式 (`() -> Any`)，只在日志级别满足条件时才执行字符串拼接，有效避免了性能浪费。
- **强大的扩展性**: 通过实现 `LogPrinter` 接口，你可以轻松定义新的日志输出目标。
- **组合式输出**: 使用内置的 `WarpLogPrinter`，可以**将多份日志同时输出**到 Logcat、文件等多个目标。
- **智能文件日志**: 内置的 `SmartFilePrinter` 提供了异步、高性能的文件日志功能，并支持日志文件的自动轮换和过期清理。
- **无线实时日志**: 内置的 `UDPLogPrinter` 和配套的 Python 脚本可以让你通过 Wi-Fi 在电脑上实时查看真机日志。
- **超长日志自动分割**: 自动处理 Android Logcat 对单条日志的长度限制。
- **零第三方依赖**: 核心模块轻量、纯净，杜绝了因传递性依赖带来的版本冲突风险。

## 安装

在你的 `build.gradle.kts` (或 `build.gradle`) 文件中添加依赖：

```kotlin
dependencies {
    implementation("io.github.flyxinhua:klog:1.0.1")
}
```
*(请将 `1.0.0` 替换为最新的版本号)*

## 基础用法

### 1. 初始化 KLog

在你的 `Application` 类的 `onCreate` 方法中完成初始化。这是最简单的配置，仅将日志输出到 Logcat。

```kotlin
import com.sanvar.log.KLog
import com.sanvar.log.LogCatPrinter
import android.app.Application

class YourApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 最简单的初始化：仅输出到 Logcat
        KLog.setup(
            isDebug = BuildConfig.DEBUG, // 根据构建类型开启/关闭日志
            output = LogCatPrinter("YourAppTag") // 设置全局 Logcat 标签
        )
    }
}
```

### 2. 记录日志

```kotlin
// 记录一个调试信息
KLog.d { "用户登录成功, username: $username" }

// 记录一个带异常的错误
try {
    // ... some code that might fail
} catch (e: Exception) {
    KLog.e(e) { "数据处理失败" }
}
```

## 高级用法: 组合式日志输出

`WarpLogPrinter` 可以让你将多个 `LogPrinter` 组合起来，让一份日志同时输出到不同目标。

以下示例展示了如何同时将日志输出到 **Logcat** 和 **本地文件**。

```kotlin
// 在 Application.onCreate() 中:
val warpLogPrinter = WarpLogPrinter()

// 添加 Logcat 打印器
warpLogPrinter.addLogPrinter(LogCatPrinter("YourAppTag"))

// 添加文件打印器
val logDir = File(this.filesDir, "logs")
warpLogPrinter.addLogPrinter(
    SmartFilePrinter(
        logDir = logDir.absolutePath,
        saveDay = 7, // 日志文件最长保留7天
    )
)

// 使用组合打印器初始化 KLog
KLog.setup(
    isDebug = BuildConfig.DEBUG,
    output = warpLogPrinter
)
```

## 高级用法: 无线实时日志 (UDP)

还在为没有数据线无法查看真机日志而烦恼吗？`UDPLogPrinter` 提供了一套完整的无线日志解决方案。

### 1. 在 App 中配置 `UDPLogPrinter`

将 `UDPLogPrinter` 添加到你的日志输出组合中。

```kotlin
// 在 Application.onCreate() 中:
val warpLogPrinter = WarpLogPrinter()
warpLogPrinter.addLogPrinter(LogCatPrinter("YourAppTag")) // 保留 Logcat 输出

// 添加 UDP 打印器
// 9999 是你电脑上监听的端口号
// "255.255.255.255" 是广播地址，也可以指定为电脑的IP地址
warpLogPrinter.addLogPrinter(UDPLogPrinter(9999, "255.255.255.255"))

KLog.setup(output = warpLogPrinter)
```

### 2. 在电脑上运行接收脚本

在你的电脑上保存以下 Python 脚本 (例如 `udplog.py`) 并运行它。确保你的手机和电脑连接在**同一个 Wi-Fi** 网络下。

```python
import socket

UDP_IP = "0.0.0.0"  # 监听所有网络接口
UDP_PORT = 9999     # 必须和 App 中配置的端口号一致

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((UDP_IP, UDP_PORT))

print(f"Listening for KLog UDP packets on port {UDP_PORT}...")

try:
    while True:
        data, addr = sock.recvfrom(4096) # 建议使用更大的缓冲区
        # UDPLogPrinter 会自动在消息前加上设备ID
        print(data.decode('utf-8'))
except KeyboardInterrupt:
    print("\nListener stopped.")
finally:
    sock.close()

```

## 架构思想: `LogPrinter`

KLog 的核心是一个简单的接口：`LogPrinter`。框架的所有输出功能都构建于此之上。你可以通过实现它来创建完全自定义的日志输出行为，例如上报到你的服务器或第三方监控平台。

```kotlin
interface LogPrinter {
    fun printer(priority: Int, message: String)
}
```

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
