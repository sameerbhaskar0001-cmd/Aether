package com.example.ai.tool.impl

import com.example.ai.tool.ToolExecutor
import com.example.ai.tool.ToolRegistry
import com.example.ai.tool.impl.device.DeviceInformationTool
import com.example.ai.tool.impl.device.MockDeviceInformationProvider
import com.example.ai.tool.impl.weather.MockWeatherProvider
import com.example.ai.tool.impl.weather.WeatherInfo
import com.example.ai.tool.impl.weather.WeatherTool
import com.example.ai.tool.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContextDependentToolsTest {

    private lateinit var registry: ToolRegistry
    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        // Build empty registry so we don't collide with default tests
        registry = ToolRegistry(registerDefaults = false)
        executor = ToolExecutor(registry)
    }

    // --- WEATHER TOOL TESTS ---

    @Test
    fun testWeatherValidLocationRequest() = runBlocking {
        val mockInfo = WeatherInfo(
            location = "Paris, France",
            currentTempCelsius = 22.5,
            feelsLikeCelsius = 21.0,
            condition = "Partly cloudy",
            humidityPercent = 60,
            windKmh = 15.0,
            precipitationMm = 0.0,
            observationTime = "2026-10-01T15:00"
        )
        val provider = MockWeatherProvider(expectedLocation = "Paris", result = mockInfo)
        val tool = WeatherTool(provider)
        registry.register(tool)

        val call = ToolCall("get_weather", mapOf("location" to "Paris"))
        val result = executor.execute(call)

        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals("Paris, France", json.getString("location"))
        assertEquals(22.5, json.getDouble("temperature_celsius"), 0.001)
        assertEquals(21.0, json.getDouble("feels_like_celsius"), 0.001)
        assertEquals("Partly cloudy", json.getString("condition"))
        assertEquals(60, json.getInt("humidity_percent"))
        assertEquals(15.0, json.getDouble("wind_speed_kmh"), 0.001)
        assertEquals(0.0, json.getDouble("precipitation_mm"), 0.001)
        assertEquals("2026-10-01T15:00", json.getString("observation_time"))
    }

    @Test
    fun testWeatherUnavailableLocation() = runBlocking {
        val provider = MockWeatherProvider(shouldFailWithLocationNotFound = true)
        val tool = WeatherTool(provider)
        registry.register(tool)

        val call = ToolCall("get_weather", mapOf("location" to "Atlantis"))
        val result = executor.execute(call)

        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Location Error"))
    }

    @Test
    fun testWeatherNetworkFailure() = runBlocking {
        val provider = MockWeatherProvider(shouldFailWithNetwork = true)
        val tool = WeatherTool(provider)
        registry.register(tool)

        val call = ToolCall("get_weather", mapOf("location" to "Paris"))
        val result = executor.execute(call)

        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Weather Fetch Failed"))
    }

    @Test
    fun testWeatherBlankLocation() = runBlocking {
        val tool = WeatherTool(MockWeatherProvider())
        registry.register(tool)

        val call = ToolCall("get_weather", mapOf("location" to "   "))
        val result = executor.execute(call)

        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Missing required parameter 'location'"))
    }

    // --- DEVICE INFORMATION TESTS ---

    @Test
    fun testDeviceInformationAllIndicators() = runBlocking {
        val mockProvider = MockDeviceInformationProvider(
            androidVersion = "15",
            manufacturer = "Google",
            model = "Pixel 9 Pro",
            appVersion = "2.3.4",
            availableStorage = 100_000L,
            totalStorage = 200_000L,
            batteryPct = 88,
            batteryStatus = "Charging",
            network = "Wi-Fi"
        )
        val tool = DeviceInformationTool(mockProvider)
        registry.register(tool)

        val call = ToolCall("get_device_info", emptyMap())
        val result = executor.execute(call)

        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals("15", json.getString("android_version"))
        assertEquals("Google", json.getString("manufacturer"))
        assertEquals("Pixel 9 Pro", json.getString("model"))
        assertEquals("2.3.4", json.getString("app_version"))
        assertEquals(200_000L, json.getLong("total_storage_bytes"))
        assertEquals(100_000L, json.getLong("available_storage_bytes"))
        assertEquals(100_000L, json.getLong("used_storage_bytes"))
        assertEquals(50.0, json.getDouble("storage_used_percentage"), 0.001)
        assertEquals(88, json.getInt("battery_percentage"))
        assertEquals("Charging", json.getString("battery_status"))
        assertEquals("Wi-Fi", json.getString("network_type"))
    }

    @Test
    fun testDeviceInformationFilteredSystem() = runBlocking {
        val mockProvider = MockDeviceInformationProvider(androidVersion = "14")
        val tool = DeviceInformationTool(mockProvider)
        registry.register(tool)

        val call = ToolCall("get_device_info", mapOf("indicator" to "system"))
        val result = executor.execute(call)

        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals("14", json.getString("android_version"))
        // Storage, battery, and network must NOT be populated when filtered to 'system'
        assertTrue(!json.has("total_storage_bytes"))
        assertTrue(!json.has("battery_percentage"))
        assertTrue(!json.has("network_type"))
    }

    @Test
    fun testDeviceInformationFilteredBattery() = runBlocking {
        val mockProvider = MockDeviceInformationProvider(batteryPct = 95)
        val tool = DeviceInformationTool(mockProvider)
        registry.register(tool)

        val call = ToolCall("get_device_info", mapOf("indicator" to "battery"))
        val result = executor.execute(call)

        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals(95, json.getInt("battery_percentage"))
        assertTrue(!json.has("android_version"))
        assertTrue(!json.has("total_storage_bytes"))
        assertTrue(!json.has("network_type"))
    }

    // --- TOOL INTEGRATION & REGISTRATION TESTS ---

    @Test
    fun testToolIntegrationDefaults() {
        val prodRegistry = ToolRegistry(registerDefaults = true)
        assertTrue(prodRegistry.contains("get_weather"))
        assertTrue(prodRegistry.contains("get_device_info"))
        assertTrue(prodRegistry.contains("calculator"))
        assertTrue(prodRegistry.contains("get_current_time"))
        assertTrue(prodRegistry.contains("unit_converter"))
        assertTrue(prodRegistry.contains("echo"))
    }
}
