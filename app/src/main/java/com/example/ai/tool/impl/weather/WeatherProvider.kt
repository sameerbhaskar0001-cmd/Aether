package com.example.ai.tool.impl.weather

/**
 * Common model representing timezone-aware weather forecast.
 */
data class WeatherInfo(
    val location: String,
    val currentTempCelsius: Double,
    val feelsLikeCelsius: Double,
    val condition: String,
    val humidityPercent: Int,
    val windKmh: Double,
    val precipitationMm: Double,
    val observationTime: String
)

/**
 * Common, platform-neutral weather fetching contract.
 */
interface WeatherProvider {
    suspend fun getWeather(location: String): WeatherInfo
}
