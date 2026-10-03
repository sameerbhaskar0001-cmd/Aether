package com.example.ai

import com.example.ai.provider.AIProvider
import com.example.ai.provider.GeminiProvider
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderRole
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Service adapter bridging ChatViewModel / AssistantService contract to the new AIProvider interface.
 */
class GeminiAssistantService(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val provider: AIProvider = GeminiProvider(timeProvider = timeProvider)
) : AssistantService {

    override fun generateResponseStream(
        userMessage: String, 
        history: List<Message>,
        relevantMemories: List<com.example.data.model.Memory>,
        fileContext: String?
    ): Flow<String> {
        val providerHistory = history
            .filter { it.status == MessageStatus.SENT && it.content.isNotBlank() }
            .map { msg ->
                ProviderMessage(
                    role = when (msg.sender) {
                        Sender.USER -> ProviderRole.USER
                        Sender.ASSISTANT -> ProviderRole.ASSISTANT
                    },
                    content = msg.content
                )
            }

        val historyTexts = history
            .filter { it.status == MessageStatus.SENT && it.content.isNotBlank() && it.sender == Sender.USER }
            .map { it.content }

        val langContext = com.example.ai.language.LanguageDetector.determineLanguageContext(userMessage, historyTexts)
        val styleContext = com.example.ai.style.ConversationStyleDetector.determineStyleContext(userMessage, historyTexts)
        val baseInstruction = com.example.ai.style.ConversationStyleDetector.buildCombinedInstruction(langContext, styleContext)

        // Process message for persistent preference creation/updates/removals
        com.example.ai.preference.CommunicationPreferenceDetector.processMessageForPreferences(userMessage)

        // Combine language, style, and persistent preferences into final instruction
        val finalInstruction = com.example.ai.preference.CommunicationPreferenceDetector.appendPreferenceInstructions(baseInstruction)

        // Resolve conversation continuity/context references
        val resolvedContext = com.example.ai.context.ConversationContextResolver.resolve(userMessage, history)

        val request = ProviderRequest(
            userMessage = userMessage,
            history = providerHistory,
            relevantMemories = relevantMemories,
            languageHint = finalInstruction,
            conversationContext = resolvedContext,
            fileContext = fileContext
        )

        return provider.generateStream(request).map { chunk ->
            chunk.textDelta
        }
    }
}
