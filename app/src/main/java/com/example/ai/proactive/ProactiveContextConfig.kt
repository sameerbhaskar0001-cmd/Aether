package com.example.ai.proactive

data class ProactiveContextConfig(
    val maxMessages: Int = 6,
    val maxCharacters: Int = 1000,
    val maxUserTurns: Int = 3,
    val topicShiftOverlapThreshold: Double = 0.05,
    val minMeaningfulWordCount: Int = 2,
    val rapidEvaluationCooldownMs: Long = 1000L,
    val trivialKeywords: Set<String> = DEFAULT_TRIVIAL_KEYWORDS
) {
    companion object {
        val DEFAULT_TRIVIAL_KEYWORDS = setOf(
            "ok", "okay", "hmm", "hmmm", "yes", "no", "thanks", "thank you",
            "thx", "k", "yep", "nope", "sure", "got it", "cool", "alright",
            "fine", "hi", "hello", "hey", "bye", "goodbye", "nice", "great",
            "understood", "agreed", "mhm", "aha"
        )
    }
}
