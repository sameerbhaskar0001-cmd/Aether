package com.example.ai

import com.example.data.model.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AssistantService {
    val lastUsedProviderFlow: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)

    /**
     * Generates a response based on the latest user message and history.
     * Returns a Flow of chunks (or word-by-word stream) representing the generated text.
     */
    fun generateResponseStream(
        userMessage: String, 
        history: List<Message>,
        relevantMemories: List<com.example.data.model.Memory> = emptyList(),
        fileContext: String? = null,
        isIncognito: Boolean = false
    ): Flow<String>
}

class MockAssistantService : AssistantService {
    override val lastUsedProviderFlow: Flow<String?> = kotlinx.coroutines.flow.flowOf("MOCK")

    private val responses = listOf(
        "I am Aether, your personal cognitive assistant. I am fully initialized and ready to operate.",
        "Your thoughts dictate my interface. How shall we expand this workspace today?",
        "A minimal layout is the ultimate clarity. I am structured for absolute focus, designed purely to assist you without noise.",
        "Understood. The foundation is solid. I can process logic, manage persistent state, and serve as your central intelligence hub.",
        "Let us focus on building a robust local shell. We can implement persistent local databases, custom model switching, or complex contextual memory next."
    )

    override fun generateResponseStream(
        userMessage: String, 
        history: List<Message>,
        relevantMemories: List<com.example.data.model.Memory>,
        fileContext: String?,
        isIncognito: Boolean
    ): Flow<String> = flow {
        // Choose a response based on keywords or cycle through them
        val text = selectResponse(userMessage)
        
        // Simulate thinking latency
        delay(1200)

        // Stream word-by-word with small delay
        val words = text.split(" ")
        val currentResponse = StringBuilder()
        
        for ((index, word) in words.withIndex()) {
            if (index > 0) {
                currentResponse.append(" ")
            }
            currentResponse.append(word)
            emit(currentResponse.toString())
            delay(100) // 100ms per word streaming
        }
    }

    private fun selectResponse(message: String): String {
        val lowercase = message.lowercase().trim()
        return when {
            lowercase.contains("hello") || lowercase.contains("hi") || lowercase.contains("hey") -> {
                "Hello. I am Aether. I am here to assist you with absolute focus. What shall we achieve today?"
            }
            lowercase.contains("who are you") || lowercase.contains("name") -> {
                "I am Aether—a highly structured, minimalist personal AI assistant designed for absolute speed and focus."
            }
            lowercase.contains("help") || lowercase.contains("capabilities") || lowercase.contains("what can you do") -> {
                "Currently, I serve as your local minimalist assistant. In future iterations, we can configure: \n\n• Advanced multi-model reasoning\n• Local and cloud memory vaults\n• Real-time tools and task execution\n• Audio/voice and visual inputs"
            }
            lowercase.contains("clear") -> {
                "Workspace cleared. Standing by for new instructions."
            }
            else -> {
                // Return a random response from our predefined beautiful assistance quotes
                responses.random()
            }
        }
    }
}
