package com.example.ai.tool.impl

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import org.json.JSONObject

/**
 * Deterministic math calculator tool.
 * Supports +, -, *, /, %, decimal numbers and parentheses.
 * Safely validates inputs with no arbitrary code execution or reflection.
 */
class CalculatorTool : Tool {
    override val name: String = "calculator"
    override val description: String = "Evaluates basic mathematical expressions containing addition (+), subtraction (-), multiplication (*), division (/), modulo (%), decimals, and parentheses."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "expression",
                type = ToolParameterType.STRING,
                description = "The math expression to evaluate (e.g. '(12 + 3.5) * 2 / 5').",
                isRequired = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val expression = arguments["expression"]?.toString() ?: ""
        if (expression.isBlank()) {
            return ToolResult.error(name, "Expression cannot be blank.")
        }

        return try {
            val parser = ExpressionParser(expression)
            val result = parser.evaluate()
            val json = JSONObject().apply {
                put("expression", expression)
                put("result", result)
            }
            ToolResult.success(name, json.toString())
        } catch (e: ArithmeticException) {
            ToolResult.error(name, "Math Error: ${e.message ?: "Evaluation failed"}")
        } catch (e: Exception) {
            ToolResult.error(name, "Malformed Expression: ${e.message ?: "Invalid expression structure"}")
        }
    }

    private class ExpressionParser(private val expr: String) {
        private val sanitized = expr.replace(" ", "")
        private var pos = 0

        fun evaluate(): Double {
            if (sanitized.isEmpty()) throw IllegalArgumentException("Empty expression")
            val finalValue = parseExpression()
            if (pos < sanitized.length) {
                throw IllegalArgumentException("Unexpected characters remaining after parsing: '${sanitized.substring(pos)}'")
            }
            return finalValue
        }

        private fun peek(): Char = if (pos < sanitized.length) sanitized[pos] else '\u0000'
        private fun consume(): Char = sanitized[pos++]

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                val next = peek()
                if (next == '+' || next == '-') {
                    consume()
                    val right = parseTerm()
                    if (next == '+') value += right else value -= right
                } else {
                    break
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                val next = peek()
                if (next == '*' || next == '/' || next == '%') {
                    consume()
                    val right = parseFactor()
                    if (next == '*') {
                        value *= right
                    } else if (next == '/') {
                        if (right == 0.0) throw ArithmeticException("Division by zero")
                        value /= right
                    } else {
                        if (right == 0.0) throw ArithmeticException("Modulo by zero")
                        value %= right
                    }
                } else {
                    break
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            val next = peek()
            return if (next == '+') {
                consume()
                parseFactor()
            } else if (next == '-') {
                consume()
                -parseFactor()
            } else if (next == '(') {
                consume() // consume '('
                val value = parseExpression()
                if (pos >= sanitized.length || consume() != ')') {
                    throw IllegalArgumentException("Unbalanced parentheses")
                }
                value
            } else if (next.isDigit() || next == '.') {
                val start = pos
                while (peek().isDigit() || peek() == '.') {
                    consume()
                }
                val numStr = sanitized.substring(start, pos)
                numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number format: $numStr")
            } else {
                throw IllegalArgumentException("Unexpected character: '$next'")
            }
        }
    }
}
