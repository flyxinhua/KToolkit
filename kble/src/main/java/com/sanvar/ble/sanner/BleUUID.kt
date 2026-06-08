package com.sanvar.ble.sanner

import java.util.UUID

object BleUUID {
    private fun build(code: String): UUID {
        return UUID.fromString("0000$code-0000-1000-8000-00805f9b34fb")
    }

    /**
     * 切割UUID 有意义部分
     */
    private fun uuidText(uuid: UUID): String {
        return uuid.toString().substring(4, 8).uppercase()
    }


    /**
     * 定义功能类型的密封类
     */
    sealed class BleFeature(val name: String) {
        object CommonGet : BleFeature("通用获取命令")
        object CommonPost : BleFeature("通用发送命令")
        object CommonPush : BleFeature("通用推送消息")
        object Battery : BleFeature("电池电量")
        object ModelNumber : BleFeature("产品型号")
        object SerialNumber : BleFeature("序列号")
        object FirmwareVersion : BleFeature("固件版本")
        object HardwareVersion : BleFeature("硬件版本")
        object SoftwareVersion : BleFeature("软件版本")
        object ManufacturerName : BleFeature("制造商名称")
        object SystemId : BleFeature("系统标识符")
        object HearRate : BleFeature("心率")
        object Power : BleFeature("功率")
        object SpeedCad : BleFeature("速度&踏频")
        data class Unknown(val uuid: String) : BleFeature("未知功能")
    }

    /**
     * 核心方法：识别 UUID 对应的功能
     */
    fun identify(uuid: UUID): BleFeature {
        return when (uuid) {
            COMMON_GET -> BleFeature.CommonGet
            COMMON_POST -> BleFeature.CommonPost
            COMMON_PUSH -> BleFeature.CommonPush
            BATTERY -> BleFeature.Battery
            MODEL -> BleFeature.ModelNumber
            SERIAL -> BleFeature.SerialNumber
            FIRM_VERSION -> BleFeature.FirmwareVersion
            HARD_VERSION -> BleFeature.HardwareVersion
            SOFT_VERSION -> BleFeature.SoftwareVersion
            MANU -> BleFeature.ManufacturerName
            SYSID -> BleFeature.SystemId
            HEART_RATE -> BleFeature.HearRate
            POWER -> BleFeature.Power
            SPEED_CAD -> BleFeature.SpeedCad
            else -> BleFeature.Unknown(uuidText(uuid))
        }
    }

    /**
     * 通用的GET 命令
     */
    val COMMON_GET = build("fda1")

    /**
     * 通用的POST 命令
     */
    val COMMON_POST = build("fda2")

    /**
     * 通用的 PUSH 命令
     */
    val COMMON_PUSH = build("fda3")


    /**
     * 电池电量
     */
    val BATTERY = build("2a19")

    /**
     * SYSID
     */
    val SYSID = build("2a23")

    /**
     * MODEL
     */
    val MODEL = build("2a24")

    /**
     * 序列号？
     */
    val SERIAL = build("2a25")

    /**
     * 固件版本
     */
    val FIRM_VERSION = build("2a26")

    /**
     *  硬件版本
     */
    val HARD_VERSION = build("2a27")

    /**
     * 软件版本
     */
    val SOFT_VERSION = build("2a28")

    /**
     * MANU
     */
    val MANU = build("2a29")

    /**
     * 心率服务与特征
     */
    val HEART_RATE = build("2a37")

    /**
     * 骑行速度踏频 (CSC)
     */
    val SPEED_CAD = build("2a5b")

    /**
     * 骑行功率 (CP)
     */
    val POWER = build("2a63")

    /**
     * CSC Feature (0x2A5C)
     * 用于读取传感器支持的功能 (Velocity, Cadence, etc.)
     */
    val CSC_FEATURE = build("2a5c")


    /**
     * ==================== Service UUIDs ====================
     */

    /**
     * 心率服务 (0x180D)
     */
    val SERVICE_HEART_RATE = build("180d")

    /**
     * 骑行功率服务 (0x1818)
     */
    val SERVICE_CYCLING_POWER = build("1818")

    /**
     * 骑行速度与踏频服务 (0x1816)
     */
    val SERVICE_CSC = build("1816")

    /**
     * 通用的 FD0A
     */
    val COMMON_FD0A = build("fd0a")

}