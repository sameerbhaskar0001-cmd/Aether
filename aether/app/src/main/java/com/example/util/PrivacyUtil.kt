package com.example.util

object PrivacyUtil {
    fun sanitizeErrorMessage(message: String?): String {
        if (message == null) return "Unknown error"
        // Redact key=... query param
        var sanitized = message.replace(Regex("key=[^&\\s\\?]+"), "key=REDACTED")
        // Redact generic Gemini API key patterns
        sanitized = sanitized.replace(Regex("AIzaSy[a-zA-Z0-9_-]{33}"), "REDACTED_API_KEY")
        // Redact Bearer auth tokens
        sanitized = sanitized.replace(Regex("Bearer\\s+[A-Za-z0-9_\\-\\.]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
        // Redact Groq API key patterns
        sanitized = sanitized.replace(Regex("gsk_[A-Za-z0-9_]+", RegexOption.IGNORE_CASE), "REDACTED_API_KEY")
        return sanitized
    }

    fun sanitizeLog(message: String?): String {
        return sanitizeErrorMessage(message)
    }
}
