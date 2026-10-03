package com.example.ai.tool.impl

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import org.json.JSONObject

/**
 * Deterministic physical unit converter tool.
 * Supports length, weight/mass, volume, temperature, time, area, and speed.
 */
class UnitConversionTool : Tool {
    override val name: String = "unit_converter"
    override val description: String = "Converts physical values between different compatible units of length, mass/weight, volume, temperature, time, area, and speed."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "value",
                type = ToolParameterType.NUMBER,
                description = "The numeric value to convert (e.g. 100.5).",
                isRequired = true
            ),
            ToolParameter(
                name = "from_unit",
                type = ToolParameterType.STRING,
                description = "The source unit to convert from (e.g. 'm', 'Celsius', 'kg').",
                isRequired = true
            ),
            ToolParameter(
                name = "to_unit",
                type = ToolParameterType.STRING,
                description = "The destination unit to convert to (e.g. 'feet', 'Fahrenheit', 'lbs').",
                isRequired = true
            )
        )
    )

    private enum class UnitCategory {
        LENGTH, MASS, TEMPERATURE, VOLUME, TIME, AREA, SPEED, UNKNOWN
    }

    private fun normalizeUnit(unit: String): String {
        val lower = unit.trim().lowercase()
        return when (lower) {
            "millimeter", "millimetre", "mm" -> "mm"
            "centimeter", "centimetre", "cm" -> "cm"
            "meter", "metre", "m" -> "m"
            "kilometer", "kilometre", "km" -> "km"
            "inch", "inches", "in" -> "inch"
            "foot", "feet", "ft" -> "foot"
            "yard", "yards", "yd" -> "yard"
            "mile", "miles", "mi" -> "mile"
            
            "milligram", "milligramme", "mg" -> "mg"
            "gram", "g" -> "g"
            "kilogram", "kg" -> "kg"
            "ounce", "ounces", "oz" -> "ounce"
            "pound", "pounds", "lb", "lbs" -> "pound"
            
            "celsius", "c" -> "celsius"
            "fahrenheit", "f" -> "fahrenheit"
            "kelvin", "k" -> "kelvin"
            
            "milliliter", "millilitre", "ml" -> "ml"
            "liter", "litre", "l" -> "litre"
            "gallon", "gallons", "gal" -> "gallon"
            
            "second", "seconds", "s", "sec" -> "seconds"
            "minute", "minutes", "min" -> "minutes"
            "hour", "hours", "hr", "hrs", "h" -> "hours"
            "day", "days", "d" -> "days"
            
            "square metre", "square meter", "sq m", "sqm" -> "square metre"
            "square kilometre", "square kilometer", "sq km", "sqkm" -> "square kilometre"
            "square foot", "square feet", "sq ft", "sqft" -> "square foot"
            
            "m/s", "mps" -> "m/s"
            "km/h", "kmh", "kph" -> "km/h"
            "mph", "mi/h" -> "mph"
            
            else -> lower
        }
    }

    private fun getUnitCategory(normalizedUnit: String): UnitCategory {
        return when (normalizedUnit) {
            "mm", "cm", "m", "km", "inch", "foot", "yard", "mile" -> UnitCategory.LENGTH
            "mg", "g", "kg", "ounce", "pound" -> UnitCategory.MASS
            "celsius", "fahrenheit", "kelvin" -> UnitCategory.TEMPERATURE
            "ml", "litre", "gallon" -> UnitCategory.VOLUME
            "seconds", "minutes", "hours", "days" -> UnitCategory.TIME
            "square metre", "square kilometre", "square foot" -> UnitCategory.AREA
            "m/s", "km/h", "mph" -> UnitCategory.SPEED
            else -> UnitCategory.UNKNOWN
        }
    }

    private val lengthFactors = mapOf(
        "mm" to 0.001,
        "cm" to 0.01,
        "m" to 1.0,
        "km" to 1000.0,
        "inch" to 0.0254,
        "foot" to 0.3048,
        "yard" to 0.9144,
        "mile" to 1609.344
    )

    private val massFactors = mapOf(
        "mg" to 0.001,
        "g" to 1.0,
        "kg" to 1000.0,
        "ounce" to 28.349523125,
        "pound" to 453.59237
    )

    private val volumeFactors = mapOf(
        "ml" to 1.0,
        "litre" to 1000.0,
        "gallon" to 3785.411784
    )

    private val timeFactors = mapOf(
        "seconds" to 1.0,
        "minutes" to 60.0,
        "hours" to 3600.0,
        "days" to 86400.0
    )

    private val areaFactors = mapOf(
        "square metre" to 1.0,
        "square kilometre" to 1000000.0,
        "square foot" to 0.09290304
    )

    private val speedFactors = mapOf(
        "m/s" to 1.0,
        "km/h" to 1.0 / 3.6,
        "mph" to 0.44704
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val rawValue = arguments["value"]
        val fromUnitInput = arguments["from_unit"]?.toString() ?: ""
        val toUnitInput = arguments["to_unit"]?.toString() ?: ""

        val value = when (rawValue) {
            is Number -> rawValue.toDouble()
            is String -> rawValue.toDoubleOrNull()
            else -> null
        } ?: return ToolResult.error(name, "Invalid numeric value: $rawValue")

        val fromNormalized = normalizeUnit(fromUnitInput)
        val toNormalized = normalizeUnit(toUnitInput)

        val fromCategory = getUnitCategory(fromNormalized)
        val toCategory = getUnitCategory(toNormalized)

        if (fromCategory == UnitCategory.UNKNOWN) {
            return ToolResult.error(name, "Unsupported source unit: '$fromUnitInput'")
        }
        if (toCategory == UnitCategory.UNKNOWN) {
            return ToolResult.error(name, "Unsupported target unit: '$toUnitInput'")
        }
        if (fromCategory != toCategory) {
            return ToolResult.error(name, "Incompatible conversion from '$fromUnitInput' to '$toUnitInput'")
        }

        val resultValue = when (fromCategory) {
            UnitCategory.TEMPERATURE -> convertTemperature(value, fromNormalized, toNormalized)
            UnitCategory.LENGTH -> convertWithFactors(value, fromNormalized, toNormalized, lengthFactors)
            UnitCategory.MASS -> convertWithFactors(value, fromNormalized, toNormalized, massFactors)
            UnitCategory.VOLUME -> convertWithFactors(value, fromNormalized, toNormalized, volumeFactors)
            UnitCategory.TIME -> convertWithFactors(value, fromNormalized, toNormalized, timeFactors)
            UnitCategory.AREA -> convertWithFactors(value, fromNormalized, toNormalized, areaFactors)
            UnitCategory.SPEED -> convertWithFactors(value, fromNormalized, toNormalized, speedFactors)
            UnitCategory.UNKNOWN -> return ToolResult.error(name, "Unsupported category")
        }

        val json = JSONObject().apply {
            put("value", value)
            put("from_unit", fromNormalized)
            put("to_unit", toNormalized)
            put("converted_value", resultValue)
        }

        return ToolResult.success(name, json.toString())
    }

    private fun convertWithFactors(value: Double, from: String, to: String, factors: Map<String, Double>): Double {
        val fromFactor = factors[from] ?: 1.0
        val toFactor = factors[to] ?: 1.0
        val baseValue = value * fromFactor
        return baseValue / toFactor
    }

    private fun convertTemperature(value: Double, from: String, to: String): Double {
        val celsiusValue = when (from) {
            "celsius" -> value
            "fahrenheit" -> (value - 32.0) * 5.0 / 9.0
            "kelvin" -> value - 273.15
            else -> value
        }
        return when (to) {
            "celsius" -> celsiusValue
            "fahrenheit" -> celsiusValue * 9.0 / 5.0 + 32.0
            "kelvin" -> celsiusValue + 273.15
            else -> celsiusValue
        }
    }
}
