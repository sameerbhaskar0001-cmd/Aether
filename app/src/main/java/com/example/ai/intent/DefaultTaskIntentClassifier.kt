package com.example.ai.intent

import com.example.ai.provider.models.ProviderRequest
import java.util.Locale

/**
 * Default implementation of [TaskIntentClassifier].
 *
 * Rules:
 * 1. Explicit structured flags (hasVisionInput, requiresWebSearch, requiresToolCalling) have highest priority.
 * 2. File context indicates FILE_ANALYSIS without assuming tool calling or specific providers.
 * 3. Never infers VISION_ANALYSIS or WEB_RESEARCH from plain words ("image", "search", etc.) without structured signals.
 * 4. Ambiguous or unstructured input returns [TaskIntent.UNKNOWN] with low confidence.
 * 5. Completely pure: no network, no persistence, no side effects, no provider dependencies.
 */
class DefaultTaskIntentClassifier : TaskIntentClassifier {

    companion object {
        // Math / Calculation pattern: requires actual numbers and arithmetic operators
        private val CALCULATION_REGEX = Regex(
            """(?i)^\s*(calculate|compute|solve|eval|evaluate)?\s*\(?[-+]?\d+(\.\d+)?\)?\s*[\+\-\*\/\^%]\s*\(?[-+]?\d+(\.\d+)?\)?(\s*[\+\-\*\/\^%]\s*\(?[-+]?\d+(\.\d+)?\)?)*\s*(=|\?)?\s*$"""
        )
        private val EXPLICIT_CALCULATE_PREFIX = Regex(
            """(?i)^\s*(calculate|compute|solve)\s+.+"""
        )

        // Unit Conversion pattern: "convert X unit to/into Y unit"
        private val UNIT_CONVERSION_REGEX = Regex(
            """(?i)^\s*(convert|change)?\s*[-+]?\d+(\.\d+)?\s*(km|kilometers|miles|mi|m|meters|cm|inches|in|ft|feet|kg|kilograms|lbs|pounds|celsius|fahrenheit|c|f|liters|gallons)\s+(to|into|in)\s+(km|kilometers|miles|mi|m|meters|cm|inches|in|ft|feet|kg|kilograms|lbs|pounds|celsius|fahrenheit|c|f|liters|gallons)\s*\??\s*$"""
        )

        // Date / Time pattern: asking for current time/date
        private val DATE_TIME_REGEX = Regex(
            """(?i)^\s*(what('s| is)|tell me|current)\s+(the\s+)?(time|date|day|current time|today's date|current date)(\s+(now|today|in utc|here))?\s*\??\s*$"""
        )
        private val TIME_PHRASES = setOf(
            "what time is it", "what's the time", "what day is it", "what is today's date",
            "what's today's date", "current time", "current date", "today's date"
        )

        // Weather pattern: asking for current weather forecast
        private val WEATHER_REGEX = Regex(
            """(?i)^\s*(what('s| is)|how is|tell me)?\s*(the\s+)?(weather|temperature|forecast|climate)\s+(in|at|for|today|tomorrow)\s+[a-zA-Z\s,]+\??\s*$"""
        )
        private val IS_IT_RAINING_REGEX = Regex(
            """(?i)^\s*(is it raining|will it rain|is it snowing)\s+(in|at|today|tomorrow)\s+[a-zA-Z\s,]+\??\s*$"""
        )

        // Code patterns: code blocks, language keywords, or code requests
        private val CODE_KEYWORDS_REGEX = Regex(
            """(?i)\b(fun\s+[a-zA-Z0-9_]+\s*\(|def\s+[a-zA-Z0-9_]+\s*\(|class\s+[a-zA-Z0-9_]+|function\s+[a-zA-Z0-9_]+\s*\(|SELECT\s+.+\s+FROM\s+|public\s+static\s+void\s+main)\b"""
        )
        private val CODE_REQUEST_REGEX = Regex(
            """(?i)^\s*(write|debug|refactor|fix|implement|create)\s+(a\s+)?(function|script|algorithm|code|method|class|unit test|regex|program|query)\s+(in|to|for|that)\s+.+"""
        )

        // Productivity pattern: reminders, todos, alarms, calendar
        private val PRODUCTIVITY_REGEX = Regex(
            """(?i)^\s*(create|add|set|schedule)\s+(a\s+)?(todo|task|reminder|meeting|calendar event|alarm|note)\s+(for|at|to|called)?\s*.+"""
        )

        // Conversational greetings
        private val GREETING_REGEX = Regex(
            """(?i)^\s*(hi|hello|hey|howdy|hola|good morning|good afternoon|good evening|greetings)\s*([!.,]|\b).*"""
        )
        private val PLEASANTRY_REGEX = Regex(
            """(?i)^\s*(thanks(\s+(a\s+lot|so\s+much|very\s+much))?|thank\s+you(\s+(so\s+much|very\s+much))?|how\s+are\s+you(\s+doing)?|what('s|\s+is)\s+up|who\s+are\s+you|nice\s+to\s+meet\s+you)\s*[?!.]*\s*$"""
        )

        // Knowledge question pattern
        private val KNOWLEDGE_REGEX = Regex(
            """(?i)^\s*(what is|what are|who is|who was|who were|explain|describe|tell me about|how does|why does|history of)\s+[a-zA-Z0-9\s-]{3,}\??\s*$"""
        )
    }

    override fun classify(request: ProviderRequest): TaskIntentResult {
        val signals = mutableListOf<String>()
        val secondary = mutableListOf<TaskIntent>()

        // 1. Explicit Structured Signals (Priority 1)
        if (request.hasVisionInput) {
            signals.add("EXPLICIT_VISION_INPUT")
            if (!request.fileContext.isNullOrBlank()) {
                secondary.add(TaskIntent.FILE_ANALYSIS)
                signals.add("STRUCTURED_FILE_ATTACHMENT")
            }
            return TaskIntentResult(
                primaryIntent = TaskIntent.VISION_ANALYSIS,
                secondaryIntents = secondary,
                confidence = 1.0f,
                reasoningSignals = signals
            )
        }

        if (request.requiresWebSearch) {
            signals.add("EXPLICIT_WEB_SEARCH")
            if (!request.fileContext.isNullOrBlank()) {
                secondary.add(TaskIntent.FILE_ANALYSIS)
                signals.add("STRUCTURED_FILE_ATTACHMENT")
            }
            return TaskIntentResult(
                primaryIntent = TaskIntent.WEB_RESEARCH,
                secondaryIntents = secondary,
                confidence = 1.0f,
                reasoningSignals = signals
            )
        }

        // 2. Structured File Context (Priority 2)
        if (!request.fileContext.isNullOrBlank()) {
            signals.add("STRUCTURED_FILE_ATTACHMENT")
            if (request.requiresToolCalling) {
                secondary.add(TaskIntent.PRODUCTIVITY)
                signals.add("EXPLICIT_TOOL_CALLING")
            }
            return TaskIntentResult(
                primaryIntent = TaskIntent.FILE_ANALYSIS,
                secondaryIntents = secondary,
                confidence = 0.95f,
                reasoningSignals = signals
            )
        }

        // 3. Explicit Tool Requirement without file attachment (Priority 3)
        if (request.requiresToolCalling) {
            signals.add("EXPLICIT_TOOL_CALLING")
            // Analyze message to see if specific tool intent can be refined
            val rawMsg = request.userMessage.trim()
            val toolIntent = when {
                isCalculation(rawMsg) -> {
                    signals.add("CALCULATION_PATTERN_MATCH")
                    TaskIntent.CALCULATION
                }
                isDateTime(rawMsg) -> {
                    signals.add("DATE_TIME_PATTERN_MATCH")
                    TaskIntent.DATE_TIME
                }
                isUnitConversion(rawMsg) -> {
                    signals.add("UNIT_CONVERSION_PATTERN_MATCH")
                    TaskIntent.UNIT_CONVERSION
                }
                else -> TaskIntent.PRODUCTIVITY
            }
            return TaskIntentResult(
                primaryIntent = toolIntent,
                secondaryIntents = secondary,
                confidence = 1.0f,
                reasoningSignals = signals
            )
        }

        // 4. Natural-Language Message Pattern Analysis
        val rawMessage = request.userMessage.trim()
        if (rawMessage.isBlank()) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.UNKNOWN,
                confidence = 0.0f,
                reasoningSignals = listOf("EMPTY_MESSAGE")
            )
        }

        // Check Calculation
        if (isCalculation(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.CALCULATION,
                confidence = 0.92f,
                reasoningSignals = listOf("CALCULATION_PATTERN_MATCH")
            )
        }

        // Check Unit Conversion
        if (isUnitConversion(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.UNIT_CONVERSION,
                confidence = 0.92f,
                reasoningSignals = listOf("UNIT_CONVERSION_PATTERN_MATCH")
            )
        }

        // Check Date / Time
        if (isDateTime(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.DATE_TIME,
                confidence = 0.90f,
                reasoningSignals = listOf("DATE_TIME_PATTERN_MATCH")
            )
        }

        // Check Code Syntax or Programming Query
        if (isCode(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.CODE,
                confidence = 0.90f,
                reasoningSignals = listOf("CODE_SYNTAX_OR_REQUEST")
            )
        }

        // Check Weather
        if (isWeather(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.WEATHER,
                confidence = 0.88f,
                reasoningSignals = listOf("WEATHER_QUERY_PATTERN")
            )
        }

        // Check Productivity (todos, reminders)
        if (isProductivity(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.PRODUCTIVITY,
                confidence = 0.88f,
                reasoningSignals = listOf("PRODUCTIVITY_TASK_REQUEST")
            )
        }

        // Check Conversational / General Chat
        if (isConversational(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.GENERAL_CHAT,
                confidence = 0.85f,
                reasoningSignals = listOf("CONVERSATIONAL_GREETING_OR_PLEASANTRY")
            )
        }

        // Check Explanatory Knowledge Question
        if (isKnowledge(rawMessage)) {
            return TaskIntentResult(
                primaryIntent = TaskIntent.KNOWLEDGE_QUESTION,
                confidence = 0.80f,
                reasoningSignals = listOf("KNOWLEDGE_EXPLANATION_QUERY")
            )
        }

        // Conservative Default: UNKNOWN when confidence cannot be established
        return TaskIntentResult(
            primaryIntent = TaskIntent.UNKNOWN,
            confidence = 0.15f,
            reasoningSignals = listOf("AMBIGUOUS_OR_UNSTRUCTURED_INPUT")
        )
    }

    private fun isCalculation(message: String): Boolean {
        if (CALCULATION_REGEX.matches(message)) return true
        if (EXPLICIT_CALCULATE_PREFIX.matches(message) && message.any { it.isDigit() } && message.any { it in "+-*/%^" }) return true
        return false
    }

    private fun isUnitConversion(message: String): Boolean {
        return UNIT_CONVERSION_REGEX.matches(message)
    }

    private fun isDateTime(message: String): Boolean {
        val normalized = message.lowercase(Locale.ROOT).trim().removeSuffix("?").trim()
        if (normalized in TIME_PHRASES) return true
        return DATE_TIME_REGEX.matches(message)
    }

    private fun isCode(message: String): Boolean {
        if (message.contains("```")) return true
        if (CODE_KEYWORDS_REGEX.containsMatchIn(message)) return true
        return CODE_REQUEST_REGEX.matches(message)
    }

    private fun isWeather(message: String): Boolean {
        if (WEATHER_REGEX.matches(message)) return true
        return IS_IT_RAINING_REGEX.matches(message)
    }

    private fun isProductivity(message: String): Boolean {
        return PRODUCTIVITY_REGEX.matches(message)
    }

    private fun isConversational(message: String): Boolean {
        if (message.length < 60 && GREETING_REGEX.matches(message)) return true
        return PLEASANTRY_REGEX.matches(message)
    }

    private fun isKnowledge(message: String): Boolean {
        return KNOWLEDGE_REGEX.matches(message)
    }
}
