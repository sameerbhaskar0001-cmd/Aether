package com.example.ai.tool.impl.device

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import org.json.JSONObject

/**
 * Tool exposing non-sensitive local device specifications and performance metrics to models.
 * Operates safely behind [DeviceInformationProvider] abstraction.
 */
class DeviceInformationTool(
    private val provider: DeviceInformationProvider
) : Tool {

    override val name: String = "get_device_info"
    override val description: String = "Provides local non-sensitive device specifications and system indicators (Android version, manufacturer, model, app version, storage usage, battery state, and active connectivity transport)."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "indicator",
                type = ToolParameterType.STRING,
                description = "Optional filter to retrieve only a specific information block. Supported values: 'system' (OS/Model info), 'storage' (disk volumes), 'battery' (power level/status), 'network' (transport type).",
                isRequired = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val indicatorInput = arguments["indicator"]?.toString()?.trim()?.lowercase()

        val json = JSONObject()

        // 1. Gather indicator fields
        val includeSystem = indicatorInput == null || indicatorInput == "system" || indicatorInput.isBlank()
        val includeStorage = indicatorInput == null || indicatorInput == "storage" || indicatorInput.isBlank()
        val includeBattery = indicatorInput == null || indicatorInput == "battery" || indicatorInput.isBlank()
        val includeNetwork = indicatorInput == null || indicatorInput == "network" || indicatorInput.isBlank()

        if (includeSystem) {
            json.put("android_version", provider.getAndroidVersion())
            json.put("manufacturer", provider.getManufacturer())
            json.put("model", provider.getModel())
            json.put("app_version", provider.getAppVersion())
        }

        if (includeStorage) {
            val total = provider.getTotalStorageBytes()
            val available = provider.getAvailableStorageBytes()
            val used = total - available
            json.put("total_storage_bytes", total)
            json.put("available_storage_bytes", available)
            json.put("used_storage_bytes", used)
            if (total > 0) {
                val percentage = (used.toDouble() / total.toDouble()) * 100.0
                json.put("storage_used_percentage", Math.round(percentage * 10.0) / 10.0)
            }
        }

        if (includeBattery) {
            provider.getBatteryPercentage()?.let { json.put("battery_percentage", it) }
            provider.getBatteryStatus()?.let { json.put("battery_status", it) }
        }

        if (includeNetwork) {
            json.put("network_type", provider.getNetworkType())
        }

        if (json.length() == 0) {
            return ToolResult.error(name, "Invalid indicator requested: '$indicatorInput'. Supported values are 'system', 'storage', 'battery', or 'network'.")
        }

        return ToolResult.success(name, json.toString())
    }
}
