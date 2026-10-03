package com.example.ai.tool.impl

import com.example.ai.TimeProvider
import com.example.ai.tool.ToolExecutor
import com.example.ai.tool.ToolRegistry
import com.example.ai.tool.model.ToolCall
import com.example.ai.tool.model.ToolStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UtilityToolsTest {

    private lateinit var registry: ToolRegistry
    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        registry = ToolRegistry(registerDefaults = true)
        executor = ToolExecutor(registry)
    }

    // --- CALCULATOR TESTS ---

    @Test
    fun testCalculatorNormalExpressions() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "12 + 3 * 4"))
        val result = executor.execute(call)
        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals(24.0, json.getDouble("result"), 0.001)
    }

    @Test
    fun testCalculatorDecimals() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "10.5 / 2 + 1.25"))
        val result = executor.execute(call)
        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals(6.5, json.getDouble("result"), 0.001)
    }

    @Test
    fun testCalculatorParentheses() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "(12 + 3) * 2"))
        val result = executor.execute(call)
        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals(30.0, json.getDouble("result"), 0.001)
    }

    @Test
    fun testCalculatorDivideByZero() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "5 / 0"))
        val result = executor.execute(call)
        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Division by zero"))
    }

    @Test
    fun testCalculatorModuloByZero() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "5 % 0"))
        val result = executor.execute(call)
        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Modulo by zero"))
    }

    @Test
    fun testCalculatorMalformedExpressions() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "12 + * 3"))
        val result = executor.execute(call)
        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Malformed Expression"))
    }

    @Test
    fun testCalculatorUnbalancedParentheses() = runBlocking {
        val call = ToolCall("calculator", mapOf("expression" to "(12 + 3"))
        val result = executor.execute(call)
        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Malformed Expression"))
    }

    // --- DATE/TIME TESTS ---

    private class FakeTimeProvider(
        private val timeMs: Long,
        private val zoneId: String = "America/New_York"
    ) : TimeProvider {
        override fun currentTimeMillis(): Long = timeMs
        override fun getZoneId(): String = zoneId
    }

    @Test
    fun testDateTimeOutputWithControlledClock() = runBlocking {
        // Thursday, Oct 1, 2026 20:15:30.500 UTC -> 16:15:30.500 EDT (UTC-4)
        val controlledTimeMs = 1790885730500L
        val fakeClock = FakeTimeProvider(controlledTimeMs, "America/New_York")
        val dateTimeTool = DateTimeTool(fakeClock)

        // Register custom DateTimeTool instance in a clean registry
        val customRegistry = ToolRegistry(registerDefaults = false)
        customRegistry.register(dateTimeTool)
        val customExecutor = ToolExecutor(customRegistry)

        val call = ToolCall("get_current_time", mapOf("timezone" to "America/New_York"))
        val result = customExecutor.execute(call)

        assertTrue(result.isSuccess)
        val json = JSONObject(result.content)
        assertEquals(controlledTimeMs, json.getLong("timestamp_ms"))
        assertEquals("America/New_York", json.getString("timezone"))
        assertEquals("Thursday, October 1, 2026, 4:15 PM", json.getString("friendly"))
        assertEquals("2026-10-01T16:15:30.500-04:00", json.getString("iso_8601"))
    }

    // --- UNIT CONVERSION TESTS ---

    @Test
    fun testRepresentativeUnitConversions() = runBlocking {
        // Test length: 5 miles to km
        val callLength = ToolCall("unit_converter", mapOf(
            "value" to 5.0,
            "from_unit" to "miles",
            "to_unit" to "km"
        ))
        val resultLength = executor.execute(callLength)
        assertTrue(resultLength.isSuccess)
        val jsonLength = JSONObject(resultLength.content)
        assertEquals(8.0467, jsonLength.getDouble("converted_value"), 0.01)

        // Test mass: 100 grams to ounces
        val callMass = ToolCall("unit_converter", mapOf(
            "value" to 100.0,
            "from_unit" to "g",
            "to_unit" to "ounce"
        ))
        val resultMass = executor.execute(callMass)
        assertTrue(resultMass.isSuccess)
        val jsonMass = JSONObject(resultMass.content)
        assertEquals(3.5274, jsonMass.getDouble("converted_value"), 0.01)
    }

    @Test
    fun testTemperatureConversions() = runBlocking {
        // 100 Celsius to Fahrenheit
        val callCtoF = ToolCall("unit_converter", mapOf(
            "value" to 100.0,
            "from_unit" to "Celsius",
            "to_unit" to "Fahrenheit"
        ))
        val resultCtoF = executor.execute(callCtoF)
        assertTrue(resultCtoF.isSuccess)
        val jsonCtoF = JSONObject(resultCtoF.content)
        assertEquals(212.0, jsonCtoF.getDouble("converted_value"), 0.001)

        // 0 Kelvin to Celsius
        val callKtoC = ToolCall("unit_converter", mapOf(
            "value" to 0.0,
            "from_unit" to "Kelvin",
            "to_unit" to "Celsius"
        ))
        val resultKtoC = executor.execute(callKtoC)
        assertTrue(resultKtoC.isSuccess)
        val jsonKtoC = JSONObject(resultKtoC.content)
        assertEquals(-273.15, jsonKtoC.getDouble("converted_value"), 0.001)
    }

    @Test
    fun testIncompatibleUnits() = runBlocking {
        // Try converting Celsius to miles
        val callIncompatible = ToolCall("unit_converter", mapOf(
            "value" to 100.0,
            "from_unit" to "Celsius",
            "to_unit" to "miles"
        ))
        val resultIncompatible = executor.execute(callIncompatible)
        assertTrue(resultIncompatible.isError)
        assertNotNull(resultIncompatible.errorMessage)
        assertTrue(resultIncompatible.errorMessage!!.contains("Incompatible conversion"))
    }

    @Test
    fun testInvalidUnits() = runBlocking {
        // Try converting unknown unit
        val callInvalid = ToolCall("unit_converter", mapOf(
            "value" to 100.0,
            "from_unit" to "flux_capacitor",
            "to_unit" to "miles"
        ))
        val resultInvalid = executor.execute(callInvalid)
        assertTrue(resultInvalid.isError)
        assertNotNull(resultInvalid.errorMessage)
        assertTrue(resultInvalid.errorMessage!!.contains("Unsupported source unit"))
    }

    // --- TOOL REGISTRATION & ISOLATION TESTS ---

    @Test
    fun testToolRegistrationAndExecution() {
        assertTrue(registry.contains("calculator"))
        assertTrue(registry.contains("get_current_time"))
        assertTrue(registry.contains("unit_converter"))
        assertTrue(registry.contains("echo"))
    }
}
