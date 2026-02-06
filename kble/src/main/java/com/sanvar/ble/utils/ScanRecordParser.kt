package com.sanvar.ble.utils

import java.util.UUID

/**
 * BLE 广播数据解析工具
 */
object ScanRecordParser {

    // AD Type 常量
    private const val TYPE_FLAGS = 0x01
    private const val TYPE_16BIT_UUIDS_INCOMPLETE = 0x02
    private const val TYPE_16BIT_UUIDS_COMPLETE = 0x03
    private const val TYPE_32BIT_UUIDS_INCOMPLETE = 0x04
    private const val TYPE_32BIT_UUIDS_COMPLETE = 0x05
    private const val TYPE_128BIT_UUIDS_INCOMPLETE = 0x06
    private const val TYPE_128BIT_UUIDS_COMPLETE = 0x07
    private const val TYPE_LOCAL_NAME_SHORT = 0x08
    private const val TYPE_LOCAL_NAME_COMPLETE = 0x09
    private const val TYPE_TX_POWER_LEVEL = 0x0A
    private const val TYPE_SERVICE_DATA_16BIT = 0x16
    private const val TYPE_SERVICE_DATA_32BIT = 0x20
    private const val TYPE_SERVICE_DATA_128BIT = 0x21
    private const val TYPE_MANUFACTURER_DATA = 0xFF

    /**
     * 解析结果
     */
    data class ParsedScanRecord(
        val deviceName: String? = null,
        val txPowerLevel: Int? = null,
        val serviceUuids: List<UUID> = emptyList(),
        val manufacturerData: Map<Int, ByteArray> = emptyMap(),  // key: 厂商ID
        val serviceData: Map<UUID, ByteArray> = emptyMap()
    )

    /**
     * 解析广播数据
     */
    fun parse(scanRecord: ByteArray?): ParsedScanRecord {
        if (scanRecord == null || scanRecord.isEmpty()) {
            return ParsedScanRecord()
        }

        var deviceName: String? = null
        var txPowerLevel: Int? = null
        val serviceUuids = mutableListOf<UUID>()
        val manufacturerData = mutableMapOf<Int, ByteArray>()
        val serviceData = mutableMapOf<UUID, ByteArray>()

        var index = 0
        while (index < scanRecord.size) {
            val length = scanRecord[index].toInt() and 0xFF
            if (length == 0 || index + 1 + length > scanRecord.size) break

            val type = scanRecord[index + 1].toInt() and 0xFF
            val dataStart = index + 2
            val dataLength = length - 1

            when (type) {
                TYPE_LOCAL_NAME_COMPLETE, TYPE_LOCAL_NAME_SHORT -> {
                    if (dataLength > 0) {
                        deviceName = tryParseString(scanRecord, dataStart, dataLength)
                    }
                }

                TYPE_TX_POWER_LEVEL -> {
                    if (dataLength >= 1) {
                        txPowerLevel = scanRecord[dataStart].toInt()
                    }
                }

                TYPE_16BIT_UUIDS_COMPLETE, TYPE_16BIT_UUIDS_INCOMPLETE -> {
                    parse16BitUuids(scanRecord, dataStart, dataLength, serviceUuids)
                }

                TYPE_32BIT_UUIDS_COMPLETE, TYPE_32BIT_UUIDS_INCOMPLETE -> {
                    parse32BitUuids(scanRecord, dataStart, dataLength, serviceUuids)
                }

                TYPE_128BIT_UUIDS_COMPLETE, TYPE_128BIT_UUIDS_INCOMPLETE -> {
                    parse128BitUuids(scanRecord, dataStart, dataLength, serviceUuids)
                }

                TYPE_MANUFACTURER_DATA -> {
                    if (dataLength >= 2) {
                        val manufacturerId = (scanRecord[dataStart].toInt() and 0xFF) or
                                ((scanRecord[dataStart + 1].toInt() and 0xFF) shl 8)
                        val data = scanRecord.copyOfRange(dataStart + 2, dataStart + dataLength)
                        manufacturerData[manufacturerId] = data
                    }
                }

                TYPE_SERVICE_DATA_16BIT -> {
                    if (dataLength >= 2) {
                        val uuid = parse16BitUuid(scanRecord, dataStart)
                        val data = scanRecord.copyOfRange(dataStart + 2, dataStart + dataLength)
                        serviceData[uuid] = data
                    }
                }
            }

            index += (1 + length)
        }

        return ParsedScanRecord(
            deviceName = deviceName,
            txPowerLevel = txPowerLevel,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturerData,
            serviceData = serviceData
        )
    }

    /**
     * 仅解析设备名称
     */
    fun parseDeviceName(scanRecord: ByteArray?): String? {
        if (scanRecord == null || scanRecord.isEmpty()) return null

        var index = 0
        while (index < scanRecord.size) {
            val length = scanRecord[index].toInt() and 0xFF
            if (length == 0 || index + 1 + length > scanRecord.size) break

            val type = scanRecord[index + 1].toInt() and 0xFF

            if (type == TYPE_LOCAL_NAME_COMPLETE || type == TYPE_LOCAL_NAME_SHORT) {
                val dataLength = length - 1
                if (dataLength > 0) {
                    return tryParseString(scanRecord, index + 2, dataLength)
                }
            }

            index += (1 + length)
        }
        return null
    }

    /**
     * 解析厂商数据
     * @return Pair<厂商ID, 数据>，解析失败返回 null
     */
    fun parseManufacturerData(scanRecord: ByteArray?): Pair<Int, ByteArray>? {
        if (scanRecord == null || scanRecord.isEmpty()) return null

        var index = 0
        while (index < scanRecord.size) {
            val length = scanRecord[index].toInt() and 0xFF
            if (length == 0 || index + 1 + length > scanRecord.size) break

            val type = scanRecord[index + 1].toInt() and 0xFF

            if (type == TYPE_MANUFACTURER_DATA && length >= 3) {
                val dataStart = index + 2
                val manufacturerId = (scanRecord[dataStart].toInt() and 0xFF) or
                        ((scanRecord[dataStart + 1].toInt() and 0xFF) shl 8)
                val data = scanRecord.copyOfRange(dataStart + 2, index + 1 + length)
                return manufacturerId to data
            }

            index += (1 + length)
        }
        return null
    }

    // ============== 私有方法 ==============

    private fun tryParseString(data: ByteArray, offset: Int, length: Int): String? {
        return try {
            String(data, offset, length, Charsets.UTF_8).trimEnd('\u0000')
        } catch (e: Exception) {
            null
        }
    }

    private fun parse16BitUuid(data: ByteArray, offset: Int): UUID {
        val uuid16 = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
        return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16))
    }

    private fun parse16BitUuids(data: ByteArray, offset: Int, length: Int, result: MutableList<UUID>) {
        var i = 0
        while (i + 1 < length) {
            result.add(parse16BitUuid(data, offset + i))
            i += 2
        }
    }

    private fun parse32BitUuids(data: ByteArray, offset: Int, length: Int, result: MutableList<UUID>) {
        var i = 0
        while (i + 3 < length) {
            val uuid32 = (data[offset + i].toInt() and 0xFF) or
                    ((data[offset + i + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + i + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + i + 3].toInt() and 0xFF) shl 24)
            result.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid32)))
            i += 4
        }
    }

    private fun parse128BitUuids(data: ByteArray, offset: Int, length: Int, result: MutableList<UUID>) {
        var i = 0
        while (i + 15 < length) {
            val msb = bytesToLong(data, offset + i + 8)
            val lsb = bytesToLong(data, offset + i)
            result.add(UUID(msb, lsb))
            i += 16
        }
    }

    private fun bytesToLong(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }
}