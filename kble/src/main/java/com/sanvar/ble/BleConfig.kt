package com.sanvar.ble

/**
 * 蓝牙设备配置
 */
data class BleConfig(
    /** MAC地址 */
    val macAddress: String,
    /** 是否开启守护连接 */
    val enableGuard: Boolean = false,
    /** 重连间隔（毫秒） */
    val reconnectInterval: Long = 3000L,
    /** 最大重连次数，-1表示无限 */
    val maxReconnectAttempts: Int = -1,
    /** 连接超时时间 */
    val connectionTimeout: Long = 10000L,
    /** 是否自动协商MTU */
    val autoNegotiateMtu: Boolean = true,
    /** 期望的MTU值 */
    val preferredMtu: Int = 517,
    /** 操作超时时间 */
    val operationTimeout: Long = 5000L,
    /** 是否启用辅助扫描 */
    val enableAssistScan: Boolean = true,
    val enableNotify: Boolean = true,
) {
    init {
        require(macAddress.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))) {
            "Invalid MAC address format: $macAddress"
        }
    }

    class Builder {
        private var macAddress: String = ""
        private var enableGuard: Boolean = false
        private var reconnectInterval: Long = 3000L
        private var maxReconnectAttempts: Int = -1
        private var connectionTimeout: Long = 10000L
        private var autoNegotiateMtu: Boolean = true
        private var preferredMtu: Int = 517
        private var operationTimeout: Long = 5000L
        private var enableAssistScan: Boolean = true
        private var enableNotify: Boolean = true

        fun macAddress(mac: String) = apply { macAddress = mac }
        fun enableGuard(enable: Boolean) = apply { enableGuard = enable }
        fun reconnectInterval(interval: Long) = apply { reconnectInterval = interval }
        fun maxReconnectAttempts(max: Int) = apply { maxReconnectAttempts = max }
        fun connectionTimeout(timeout: Long) = apply { connectionTimeout = timeout }
        fun autoNegotiateMtu(auto: Boolean) = apply { autoNegotiateMtu = auto }
        fun preferredMtu(mtu: Int) = apply { preferredMtu = mtu }
        fun operationTimeout(timeout: Long) = apply { operationTimeout = timeout }
        fun enableAssistScan(enable: Boolean) = apply { enableAssistScan = enable }
        fun enableNotify(enable: Boolean) = apply { enableNotify = enable }

        fun build() = BleConfig(
            macAddress, enableGuard, reconnectInterval, maxReconnectAttempts,
            connectionTimeout, autoNegotiateMtu, preferredMtu, operationTimeout,
            enableAssistScan, enableNotify
        )
    }

    companion object {
        fun builder() = Builder()
    }
}