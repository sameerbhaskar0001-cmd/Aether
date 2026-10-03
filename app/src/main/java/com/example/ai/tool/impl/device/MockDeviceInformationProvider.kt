package com.example.ai.tool.impl.device

/**
 * Deterministic Mock implementation of DeviceInformationProvider.
 * Provides static test parameters for local validation.
 */
class MockDeviceInformationProvider(
    private val androidVersion: String = "15",
    private val manufacturer: String = "Google",
    private val model: String = "Pixel 9 Pro",
    private val appVersion: String = "2.3.4",
    private val availableStorage: Long = 120_000_000_000L,
    private val totalStorage: Long = 256_000_000_000L,
    private val batteryPct: Int? = 85,
    private val batteryStatus: String? = "Discharging",
    private val network: String = "Wi-Fi"
) : DeviceInformationProvider {

    override fun getAndroidVersion(): String = androidVersion
    override fun getManufacturer(): String = manufacturer
    override fun getModel(): String = model
    override fun getAppVersion(): String = appVersion
    override fun getAvailableStorageBytes(): Long = availableStorage
    override fun getTotalStorageBytes(): Long = totalStorage
    override fun getBatteryPercentage(): Int? = batteryPct
    override fun getBatteryStatus(): String? = batteryStatus
    override fun getNetworkType(): String = network
}
