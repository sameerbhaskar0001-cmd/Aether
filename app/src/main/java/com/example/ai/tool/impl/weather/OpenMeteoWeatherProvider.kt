package com.example.ai.tool.impl.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WeatherProvider implementation leveraging free, public Open-Meteo API.
 * Requires 0 API keys and does not leak coordinates.
 */
class OpenMeteoWeatherProvider : WeatherProvider {

    override suspend fun getWeather(location: String): WeatherInfo = withContext(Dispatchers.IO) {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Location name must not be blank")

        // 1. Geocode Location to Lat/Lon
        val encodedLocation = URLEncoder.encode(trimmed, "UTF-8")
        val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedLocation&count=1&language=en"

        val geocodeResponse = fetchHttp(geocodeUrl)
        val geocodeJson = JSONObject(geocodeResponse)
        if (!geocodeJson.has("results")) {
            throw IllegalArgumentException("Location '$trimmed' could not be resolved.")
        }

        val resultsArray = geocodeJson.getJSONArray("results")
        if (resultsArray.length() == 0) {
            throw IllegalArgumentException("Location '$trimmed' could not be resolved.")
        }

        val locationObj = resultsArray.getJSONObject(0)
        val lat = locationObj.getDouble("latitude")
        val lon = locationObj.getDouble("longitude")
        val resolvedName = locationObj.optString("name", trimmed) + ", " + locationObj.optString("country", "")

        // 2. Fetch Weather for coordinates
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"

        val weatherResponse = fetchHttp(weatherUrl)
        val weatherJson = JSONObject(weatherResponse)
        if (!weatherJson.has("current")) {
            throw IllegalStateException("Weather forecast data unavailable for '$trimmed'.")
        }

        val currentObj = weatherJson.getJSONObject("current")
        val temp = currentObj.getDouble("temperature_2m")
        val feelsLike = currentObj.getDouble("apparent_temperature")
        val humidity = currentObj.getInt("relative_humidity_2m")
        val wind = currentObj.getDouble("wind_speed_10m")
        val precipitation = currentObj.getDouble("precipitation")
        val wmoCode = currentObj.getInt("weather_code")
        val timeString = currentObj.getString("time")

        WeatherInfo(
            location = resolvedName,
            currentTempCelsius = temp,
            feelsLikeCelsius = feelsLike,
            condition = interpretWeatherCode(wmoCode),
            humidityPercent = humidity,
            windKmh = wind,
            precipitationMm = precipitation,
            observationTime = timeString
        )
    }

    private fun fetchHttp(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "AetherWorkspaceAssistant/1.0")

        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IllegalStateException("Network request failed with HTTP status code: $status")
        }

        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        connection.disconnect()

        return response.toString()
    }

    private fun interpretWeatherCode(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45 -> "Foggy"
            48 -> "Depositing rime foggy"
            51 -> "Light drizzle"
            53 -> "Moderate drizzle"
            55 -> "Dense drizzle"
            61 -> "Slight rain"
            63 -> "Moderate rain"
            65 -> "Heavy rain"
            71 -> "Slight snow fall"
            73 -> "Moderate snow fall"
            75 -> "Heavy snow fall"
            80 -> "Slight rain showers"
            81 -> "Moderate rain showers"
            82 -> "Violent rain showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown weather condition"
        }
    }
}
