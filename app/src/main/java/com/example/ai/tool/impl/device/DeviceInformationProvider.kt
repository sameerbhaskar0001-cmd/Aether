package com.example.ai.tool.impl.device

/**
 * Common, platform-neutral contract for retrieving non-sensitive device info.
 */
interface DeviceInformationProvider {
    fun getAndroidVersion(): String
    fun getManufacturer(): String
    fun getModel(): String
    fun getAppVersion(): String
    fun getAvailableStorageBytes(): Long
    fun getTotalStorageBytes(): Long
    fun getBatteryPercentage(): Int?
    fun getBatteryStatus(): String?
    fun getNetworkType(): String
}
