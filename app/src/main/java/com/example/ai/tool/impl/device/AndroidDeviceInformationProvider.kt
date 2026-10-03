package com.example.ai.tool.impl.device

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Android system implementation of DeviceInformationProvider.
 * Accesses non-sensitive system indicators safely.
 */
class AndroidDeviceInformationProvider(
    private val context: Context
) : DeviceInformationProvider {

    override fun getAndroidVersion(): String = Build.VERSION.RELEASE
    override fun getManufacturer(): String = Build.MANUFACTURER
    override fun getModel(): String = Build.MODEL

    override fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    override fun getAvailableStorageBytes(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            stat.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    override fun getTotalStorageBytes(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            stat.totalBytes
        } catch (e: Exception) {
            0L
        }
    }

    override fun getBatteryPercentage(): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            null
        }
    }

    override fun getBatteryStatus(): String? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val statusInt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            when (statusInt) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "None"
            val caps = cm.getNetworkCapabilities(network) ?: return "None"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
