package com.example.ai.tool.impl.weather

/**
 * Deterministic Mock implementation of WeatherProvider for unit testing.
 * Prevents calling actual network services and allows simulating failures.
 */
class MockWeatherProvider(
    private val expectedLocation: String = "Paris",
    private val result: WeatherInfo? = WeatherInfo(
        location = "Paris, France",
        currentTempCelsius = 22.5,
        feelsLikeCelsius = 21.0,
        condition = "Partly cloudy",
        humidityPercent = 60,
        windKmh = 15.0,
        precipitationMm = 0.0,
        observationTime = "2026-10-01T15:00"
    ),
    private val shouldFailWithNetwork: Boolean = false,
    private val shouldFailWithLocationNotFound: Boolean = false
) : WeatherProvider {

    override suspend fun getWeather(location: String): WeatherInfo {
        if (shouldFailWithNetwork) {
            throw java.io.IOException("Network connection lost.")
        }
        if (shouldFailWithLocationNotFound || location != expectedLocation) {
            throw IllegalArgumentException("Location '$location' not found.")
        }
        return result ?: throw IllegalStateException("Forecast unavailable.")
    }
}
