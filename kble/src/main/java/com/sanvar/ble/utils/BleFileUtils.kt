package com.sanvar.ble.utils

import java.io.File

object BleFileUtils {

    fun file2ByteArray(file: File): ByteArray {
        return file.readBytes()
    }


    /**
     * 极速 CRC16 计算
     * 完美还原原 Java 逻辑，但去掉了低效的 % 65536，替换为位运算 and 0xFFFF
     */
    private fun calculateCRC16(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = ((crc ushr 8) or (crc shl 8)) and 0xFFFF
            crc = crc xor (data[i].toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 8) shl 4) and 0xFFFF
            crc = crc xor (((crc and 0xFF) shl 4) shl 1) and 0xFFFF
        }
        return crc
    }
}