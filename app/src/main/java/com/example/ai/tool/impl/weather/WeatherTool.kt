package com.example.ai.tool.impl.weather

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import org.json.JSONObject

/**
 * Tool exposing weather forecasting capability to models.
 * Operates deterministically via [WeatherProvider] contract.
 */
class WeatherTool(
    private val provider: WeatherProvider = OpenMeteoWeatherProvider()
) : Tool {

    override val name: String = "get_weather"
    override val description: String = "Retrieves real-time local weather details (temperature, feels-like, wind speed, humidity, precipitation, and conditions) for a specified city/location."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "location",
                type = ToolParameterType.STRING,
                description = "The target city/location name to retrieve weather for (e.g. 'Paris', 'New York', 'Tokyo').",
                isRequired = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val locationInput = arguments["location"]?.toString() ?: ""
        val trimmed = locationInput.trim()

        if (trimmed.isBlank()) {
            return ToolResult.error(name, "Location parameter cannot be blank.")
        }

        return try {
            val info = provider.getWeather(trimmed)
            val json = JSONObject().apply {
                put("location", info.location)
                put("temperature_celsius", info.currentTempCelsius)
                put("feels_like_celsius", info.feelsLikeCelsius)
                put("condition", info.condition)
                put("humidity_percent", info.humidityPercent)
                put("wind_speed_kmh", info.windKmh)
                put("precipitation_mm", info.precipitationMm)
                put("observation_time", info.observationTime)
            }
            ToolResult.success(name, json.toString())
        } catch (e: IllegalArgumentException) {
            ToolResult.error(name, "Location Error: ${e.localizedMessage ?: "Invalid location name specified."}")
        } catch (e: Exception) {
            ToolResult.error(name, "Weather Fetch Failed: ${e.localizedMessage ?: "Network or provider error occurred."}")
        }
    }
}
