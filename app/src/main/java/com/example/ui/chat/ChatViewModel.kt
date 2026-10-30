package com.example.ui.chat

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AetherApplication
import com.example.ai.AssistantService
import com.example.ai.GeminiAssistantService
import com.example.ai.MemoryService
import com.example.ai.MemoryServiceImpl
import com.example.ai.provider.selection.GeminiModelVariant
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import com.example.data.model.Memory
import com.example.data.repository.ConversationRepository
import com.example.data.repository.MemoryRepository
import com.example.ai.file.search.FileSearchQuery
import com.example.ai.file.search.FileContextBuilder
import com.example.ai.file.search.FileSearchRepository
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class PendingActionType {
    FORGET,
    PERMANENT_DELETE
}

data class PendingAction(
    val type: PendingActionType,
    val originalTarget: String,
    val candidateMemoryIds: List<String>,
    val displayedCandidates: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatViewModel @JvmOverloads constructor(
    application: Application,
    private val assistantService: AssistantService = run {
        val registry = com.example.ai.tool.ToolRegistry(application.applicationContext, registerDefaults = true)
        val executor = com.example.ai.tool.ToolExecutor(registry)
        val orchestrator = com.example.ai.tool.orchestrator.ToolOrchestrator(executor)
        GeminiAssistantService(
            toolRegistry = registry,
            toolOrchestrator = orchestrator,
            selectionManager = com.example.ai.provider.selection.ModelSelectionManager(application.applicationContext)
        )
    },
    private val memoryService: MemoryService = MemoryServiceImpl(),
    private val fileSearchRepository: FileSearchRepository = (application as AetherApplication).fileSearchRepository,
    private val selectionManager: com.example.ai.provider.selection.ModelSelectionManager = 
        com.example.ai.provider.selection.ModelSelectionManager(application.applicationContext)
) : AndroidViewModel(application) {

    // Theme State
    private val appSettingsPrefs = application.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
    private val _isDarkTheme = MutableStateFlow(appSettingsPrefs.getBoolean("is_dark_theme", true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun toggleTheme() {
        val newTheme = !_isDarkTheme.value
        _isDarkTheme.value = newTheme
        appSettingsPrefs.edit().putBoolean("is_dark_theme", newTheme).apply()
    }

    // Model Selection UI States
    private val _hasAnyKey = MutableStateFlow(com.example.util.ApiKeyStorage.hasAnyProviderConfigured(application))
    val hasAnyKey: StateFlow<Boolean> = _hasAnyKey.asStateFlow()

    private val _geminiModelVariant = MutableStateFlow(selectionManager.getGeminiModelVariant(false))
    val geminiModelVariant: StateFlow<GeminiModelVariant> = _geminiModelVariant.asStateFlow()

    fun setGeminiModelVariant(variant: GeminiModelVariant, isIncog: Boolean = _isIncognito.value) {
        selectionManager.setGeminiModelVariant(variant, isIncog)
        _geminiModelVariant.value = selectionManager.getGeminiModelVariant(isIncog)
    }

    fun toggleGeminiModelVariant() {
        val next = if (_geminiModelVariant.value == GeminiModelVariant.FLASH_2_5) {
            GeminiModelVariant.PRO_2_5
        } else {
            GeminiModelVariant.FLASH_2_5
        }
        setGeminiModelVariant(next, _isIncognito.value)
    }

    // Multimodal Image Attachment State
    private val _attachedImage = MutableStateFlow<UiImageAttachment?>(null)
    val attachedImage: StateFlow<UiImageAttachment?> = _attachedImage.asStateFlow()

    fun setAttachedImage(attachment: UiImageAttachment?) {
        _attachedImage.value = attachment
    }

    fun clearAttachedImage() {
        _attachedImage.value = null
    }

    fun refreshKeysState() {
        _hasAnyKey.value = com.example.util.ApiKeyStorage.hasAnyProviderConfigured(getApplication())
        _uiSelectionMode.value = selectionManager.getSelectionMode(_isIncognito.value)
        _uiSelectedProvider.value = selectionManager.getSelectedProvider(_isIncognito.value)
        _geminiModelVariant.value = selectionManager.getGeminiModelVariant(_isIncognito.value)
    }

    private val _uiSelectionMode = MutableStateFlow(selectionManager.getSelectionMode(false))
    val uiSelectionMode = _uiSelectionMode.asStateFlow()

    private val _uiSelectedProvider = MutableStateFlow(selectionManager.getSelectedProvider(false))
    val uiSelectedProvider = _uiSelectedProvider.asStateFlow()

    val lastResponseProvider = MutableStateFlow<String?>(null)
    val lastResponseModel = MutableStateFlow<String?>(null)

    val availableProviders: List<com.example.ai.provider.AIProviderType>
        get() = selectionManager.getAvailableProviders()

    fun updateSelectionMode(mode: com.example.ai.provider.selection.SelectionMode, isIncog: Boolean = _isIncognito.value) {
        selectionManager.setSelectionMode(mode, isIncog)
        _uiSelectionMode.value = selectionManager.getSelectionMode(isIncog)
    }

    fun updateSelectedProvider(provider: com.example.ai.provider.AIProviderType, isIncog: Boolean = _isIncognito.value) {
        selectionManager.setSelectedProvider(provider, isIncog)
        _uiSelectedProvider.value = selectionManager.getSelectedProvider(isIncog)
    }

    private val repository: ConversationRepository = 
        (application as AetherApplication).conversationRepository

    private val memoryRepository: MemoryRepository = 
        (application as AetherApplication).memoryRepository

    // Stream list of historical conversations
    val conversations: StateFlow<List<Conversation>> = repository.getAllConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Stream of all active long-term memories
    val memories: StateFlow<List<Memory>> = memoryRepository.getAllMemoriesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentConversationId = MutableStateFlow(UUID.randomUUID().toString())
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _isIncognito = MutableStateFlow(false)
    val isIncognito: StateFlow<Boolean> = _isIncognito.asStateFlow()

    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    // Phase 8D/8E Proactive Services
    private val proactiveDetector = com.example.ai.proactive.ProactiveSignalDetector()
    private val proactiveEngine = com.example.ai.proactive.ProactiveEngine()
    private val proactiveDeliveryCoordinator = com.example.ai.proactive.ProactiveDeliveryCoordinator()
    val proactiveTriggerPipeline = com.example.ai.proactive.ProactiveTriggerPipeline()

    private val _activeProactiveDelivery = MutableStateFlow<com.example.ai.proactive.ProactiveDelivery?>(null)
    val activeProactiveDelivery: StateFlow<com.example.ai.proactive.ProactiveDelivery?> = _activeProactiveDelivery.asStateFlow()

    // Screen messages (holds current conversation messages, or incognito memory messages)
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    // Job to track message flow subscription for the current conversation
    private var messageCollectionJob: Job? = null

    // In-memory list for Incognito sessions
    private var incognitoMessagesList = mutableListOf<Message>()

    init {
        observeMessagesForConversation(_currentConversationId.value)
    }

    private fun observeMessagesForConversation(conversationId: String) {
        messageCollectionJob?.cancel()
        if (_isIncognito.value) {
            _messages.value = incognitoMessagesList
            return
        }

        messageCollectionJob = viewModelScope.launch {
            repository.getMessagesForConversation(conversationId)
                .collect { dbMessages ->
                    if (!_isIncognito.value) {
                        _messages.value = dbMessages
                    }
                }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /**
     * Start a new fresh normal conversation
     */
    fun startNewConversation() {
        viewModelScope.launch {
            _isIncognito.value = false
            incognitoMessagesList.clear()
            (assistantService as? GeminiAssistantService)?.toolRegistry?.clearIncognitoSessionState()
            _pendingAction.value = null
            _activeProactiveDelivery.value = null
            _attachedImage.value = null
            _geminiModelVariant.value = selectionManager.getGeminiModelVariant(false)
            proactiveDeliveryCoordinator.clearRegistry()
            val newId = UUID.randomUUID().toString()
            _currentConversationId.value = newId
            _messages.value = emptyList()
            observeMessagesForConversation(newId)
        }
    }

    /**
     * Toggle Incognito Mode on / off
     */
    fun toggleIncognito(enabled: Boolean) {
        viewModelScope.launch {
            _isIncognito.value = enabled
            _pendingAction.value = null
            _activeProactiveDelivery.value = null
            _attachedImage.value = null
            _geminiModelVariant.value = selectionManager.getGeminiModelVariant(enabled)
            proactiveDeliveryCoordinator.clearRegistry()
            (assistantService as? GeminiAssistantService)?.toolRegistry?.clearIncognitoSessionState()
            if (enabled) {
                val incognitoId = UUID.randomUUID().toString()
                _currentConversationId.value = incognitoId
                incognitoMessagesList.clear()
                _messages.value = emptyList()
            } else {
                startNewConversation()
            }
        }
    }

    /**
     * Switch to an existing conversation
     */
    fun selectConversation(conversationId: String) {
        viewModelScope.launch {
            _isIncognito.value = false
            incognitoMessagesList.clear()
            _pendingAction.value = null
            _activeProactiveDelivery.value = null
            _attachedImage.value = null
            proactiveDeliveryCoordinator.clearRegistry()
            _currentConversationId.value = conversationId
            observeMessagesForConversation(conversationId)
        }
    }

    /**
     * Delete a conversation
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            _pendingAction.value = null
            repository.deleteConversation(conversationId)
            if (_currentConversationId.value == conversationId) {
                startNewConversation()
            }
        }
    }

    fun sendMessage(content: String = _inputText.value) {
        val currentImage = _attachedImage.value
        val rawTrimmed = content.trim()
        if (rawTrimmed.isEmpty() && currentImage == null) return

        val trimmedContent = if (rawTrimmed.isEmpty() && currentImage != null) {
            "Analyze this attached image."
        } else {
            rawTrimmed
        }

        if (content == _inputText.value) {
            _inputText.value = ""
        }
        _attachedImage.value = null

        val displayedUserContent = if (currentImage != null && rawTrimmed.isNotEmpty()) {
            "[Image Attached] $rawTrimmed"
        } else if (currentImage != null) {
            "[Image Attached] Analyze this image."
        } else {
            trimmedContent
        }

        val timestamp = System.currentTimeMillis()
        val userMessage = Message(
            content = displayedUserContent,
            sender = Sender.USER,
            timestamp = timestamp,
            status = MessageStatus.SENT
        )

        viewModelScope.launch {
            val convId = _currentConversationId.value
            val isIncog = _isIncognito.value

            if (isIncog) {
                incognitoMessagesList.add(userMessage)
                _messages.value = incognitoMessagesList.toList()
            } else {
                repository.saveMessage(convId, userMessage, false)
            }

            val currentPending = _pendingAction.value
            if (currentPending != null && currentImage == null) {
                if (isExplicitCommandPattern(trimmedContent)) {
                    _pendingAction.value = null
                    val wasCommand = handleExplicitCommand(trimmedContent, convId, isIncog)
                    if (!wasCommand) {
                        generateAssistantResponse(trimmedContent, convId, isIncog, null)
                    }
                    return@launch
                }

                if (isCancellationCommand(trimmedContent)) {
                    _pendingAction.value = null
                    postSystemResponse("Action cancelled.", convId)
                    return@launch
                }

                val selectedIdx = parseSelectionInput(trimmedContent, currentPending.candidateMemoryIds.size)
                if (selectedIdx != null) {
                    val selectedMemoryId = currentPending.candidateMemoryIds[selectedIdx]
                    val selectedMemoryContent = currentPending.displayedCandidates[selectedIdx]

                    if (isIncog) {
                        _pendingAction.value = null
                        postSystemResponse("Cannot modify memories in Incognito mode.", convId)
                        return@launch
                    }

                    when (currentPending.type) {
                        PendingActionType.FORGET -> {
                            val match = memoryRepository.getMemoryById(selectedMemoryId)
                            if (match != null) {
                                val archived = match.copy(
                                    state = "ARCHIVED",
                                    isArchived = true,
                                    lastUpdatedTimestamp = System.currentTimeMillis()
                                )
                                memoryRepository.saveMemory(archived)
                                postSystemResponse("I have forgotten and archived the memory: \"${match.content}\".", convId)
                            } else {
                                postSystemResponse("The selected memory could not be found.", convId)
                            }
                        }
                        PendingActionType.PERMANENT_DELETE -> {
                            memoryRepository.deleteMemoryById(selectedMemoryId)
                            postSystemResponse("I have permanently erased the memory: \"$selectedMemoryContent\" from my database.", convId)
                        }
                    }

                    _pendingAction.value = null
                } else {
                    val sb = StringBuilder("Invalid selection. Please choose a number between 1 and ${currentPending.candidateMemoryIds.size} corresponding to:")
                    currentPending.displayedCandidates.forEachIndexed { index, item ->
                        sb.append("\n${index + 1}. $item")
                    }
                    sb.append("\nOr type 'cancel' to exit.")
                    postSystemResponse(sb.toString(), convId)
                }
                return@launch
            }

            val wasCommand = if (currentImage == null) {
                handleExplicitCommand(trimmedContent, convId, isIncog)
            } else {
                false
            }
            if (!wasCommand) {
                generateAssistantResponse(trimmedContent, convId, isIncog, currentImage)
            }
        }
    }

    private fun generateAssistantResponse(
        userPrompt: String,
        convId: String,
        isIncog: Boolean,
        imageAttachment: UiImageAttachment? = null
    ) {
        viewModelScope.launch {
            _isThinking.value = true

            val assistantPlaceholderId = UUID.randomUUID().toString()
            val initialAssistantMessage = Message(
                id = assistantPlaceholderId,
                content = "",
                sender = Sender.ASSISTANT,
                status = MessageStatus.THINKING
            )

            if (isIncog) {
                incognitoMessagesList.add(initialAssistantMessage)
                _messages.value = incognitoMessagesList.toList()
            } else {
                repository.saveMessage(convId, initialAssistantMessage, false)
            }

            val history = _messages.value.filter { it.id != assistantPlaceholderId }

            val relevantMemories = if (isIncog) {
                emptyList()
            } else {
                try {
                    val allMemories = memoryRepository.getAllActiveMemories()
                    memoryService.getRelevantMemories(userPrompt, allMemories)
                } catch (e: Exception) {
                    val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
                    Log.e("ChatViewModel", "Failed to retrieve relevant memories: $safeErr")
                    emptyList()
                }
            }

            val fileContext = if (isIncog) {
                null
            } else {
                try {
                    val fileResults = fileSearchRepository.searchFiles(
                        FileSearchQuery(
                            queryText = userPrompt,
                            maxResults = FileContextBuilder.MAX_RESULTS,
                            maxSnippetLength = FileContextBuilder.MAX_SNIPPET_CHARS,
                            isIncognito = false
                        )
                    )
                    FileContextBuilder.build(fileResults)
                } catch (e: Exception) {
                    val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
                    Log.e("ChatViewModel", "Failed to retrieve file context: $safeErr")
                    null
                }
            }

            lastResponseProvider.value = null
            lastResponseModel.value = null
            val providerCollectJob = viewModelScope.launch {
                assistantService.lastUsedProviderFlow.collect { provider ->
                    if (provider != null) {
                        lastResponseProvider.value = provider
                    }
                }
            }
            val modelCollectJob = viewModelScope.launch {
                assistantService.lastUsedModelFlow.collect { model ->
                    if (model != null) {
                        lastResponseModel.value = model
                    }
                }
            }

            val activeVariant = _geminiModelVariant.value
            val providerImage = imageAttachment?.toProviderAttachment()

            try {
                val streamFlow = if (providerImage != null || activeVariant != GeminiModelVariant.FLASH_2_5) {
                    assistantService.generateResponseStream(
                        userMessage = userPrompt,
                        history = history,
                        relevantMemories = relevantMemories,
                        fileContext = fileContext,
                        isIncognito = isIncog,
                        imageAttachment = providerImage,
                        preferredModelId = activeVariant.modelId
                    )
                } else {
                    assistantService.generateResponseStream(
                        userMessage = userPrompt,
                        history = history,
                        relevantMemories = relevantMemories,
                        fileContext = fileContext,
                        isIncognito = isIncog
                    )
                }

                streamFlow
                    .catch { error ->
                        _isThinking.value = false
                        providerCollectJob.cancel()
                        modelCollectJob.cancel()
                        val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(error.localizedMessage)
                        val errorMsg = "Error generating response: $safeErr"
                        updateMessageStatusAndSave(convId, assistantPlaceholderId, errorMsg, MessageStatus.ERROR, isIncog)
                    }
                    .collect { streamedText ->
                        _isThinking.value = false
                        updateMessageStatusAndSave(convId, assistantPlaceholderId, streamedText, MessageStatus.SENT, isIncog)
                    }
            } finally {
                providerCollectJob.cancel()
                modelCollectJob.cancel()
            }

            if (!isIncog) {
                triggerMemoryExtraction(convId)
                runProactiveEvaluation(userPrompt)
            }
        }
    }

    /**
     * Evaluates available long-term memories and current context for proactive delivery.
     */
    suspend fun runProactiveEvaluation(
        userPrompt: String,
        event: com.example.ai.proactive.ProactiveTriggerEvent = com.example.ai.proactive.ProactiveTriggerEvent.ASSISTANT_RESPONSE_COMPLETED
    ) {
        val isIncog = _isIncognito.value
        if (isIncog) {
            _activeProactiveDelivery.value = null
            return
        }

        try {
            val history = _messages.value
            val request = proactiveTriggerPipeline.evaluateTrigger(
                event = event,
                history = history,
                newMessage = userPrompt,
                isIncognito = isIncog
            )

            if (!request.shouldEvaluate) {
                return
            }

            val activeMemories = memoryRepository.getAllActiveMemories()

            val signals = proactiveDetector.detectSignals(
                currentContext = request.boundedContext,
                isIncognito = isIncog,
                memories = activeMemories
            )

            if (signals.isEmpty()) {
                return
            }

            for (signal in signals) {
                val decision = proactiveEngine.determineDecisionForSignals(
                    currentContext = request.boundedContext,
                    isIncognito = isIncog,
                    signals = listOf(signal)
                )

                val delivery = proactiveDeliveryCoordinator.deliver(
                    signal = signal,
                    decision = decision,
                    isIncognito = isIncog
                )

                if (delivery != null) {
                    val delivered = delivery.transitionTo(com.example.ai.proactive.ProactiveDeliveryState.DELIVERED)
                    proactiveDeliveryCoordinator.transitionDeliveryState(delivery.id, com.example.ai.proactive.ProactiveDeliveryState.DELIVERED)
                    _activeProactiveDelivery.value = delivered

                    val cooldownKey = signal.sourceMemoryId ?: signal.id
                    proactiveEngine.markAsSurfaced(cooldownKey)
                    proactiveTriggerPipeline.markEvaluated(request.boundedContext)
                    break
                }
            }
        } catch (e: Exception) {
            val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
            Log.e("ChatViewModel", "Proactive evaluation failed: $safeErr")
        }
    }

    /**
     * Checks and handles proactive card expiration.
     */
    fun checkActiveProactiveDelivery(): com.example.ai.proactive.ProactiveDelivery? {
        val current = _activeProactiveDelivery.value ?: return null
        val currentTime = System.currentTimeMillis()
        if (current.expirationTimestamp != null && currentTime >= current.expirationTimestamp) {
            _activeProactiveDelivery.value = null
            proactiveDeliveryCoordinator.transitionDeliveryState(current.id, com.example.ai.proactive.ProactiveDeliveryState.EXPIRED)
            return null
        }
        return current
    }

    /**
     * User action: Dismiss the proactive card.
     */
    fun dismissProactiveDelivery(deliveryId: String) {
        val current = _activeProactiveDelivery.value
        if (current != null && current.id == deliveryId) {
            proactiveDeliveryCoordinator.transitionDeliveryState(deliveryId, com.example.ai.proactive.ProactiveDeliveryState.DISMISSED)
            _activeProactiveDelivery.value = null
        }
    }

    /**
     * User action: Acknowledge the proactive card.
     */
    fun acknowledgeProactiveDelivery(deliveryId: String) {
        val current = _activeProactiveDelivery.value
        if (current != null && current.id == deliveryId) {
            proactiveDeliveryCoordinator.transitionDeliveryState(deliveryId, com.example.ai.proactive.ProactiveDeliveryState.ACKNOWLEDGED)
            _activeProactiveDelivery.value = null
        }
    }

    private fun triggerMemoryExtraction(convId: String) {
        val inputData = androidx.work.Data.Builder()
            .putString("CONVERSATION_ID", convId)
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.ai.worker.MemoryExtractionWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<AetherApplication>()
            val db = app.database
            val jobDao = db.memoryExtractionJobDao()
            jobDao.insertOrUpdateJob(
                com.example.data.local.entity.MemoryExtractionJobEntity(
                    conversationId = convId,
                    status = "PENDING",
                    lastUpdatedTimestamp = System.currentTimeMillis(),
                    retryCount = 0
                )
            )

            androidx.work.WorkManager.getInstance(getApplication()).enqueueUniqueWork(
                "MemoryExtractionWork_$convId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }

    fun saveMemory(memory: Memory) {
        viewModelScope.launch {
            memoryRepository.saveMemory(memory)
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            memoryRepository.deleteMemoryById(id)
        }
    }

    private suspend fun updateMessageStatusAndSave(
        convId: String,
        messageId: String,
        content: String,
        status: MessageStatus,
        isIncog: Boolean
    ) {
        val updatedMessage = Message(
            id = messageId,
            content = content,
            sender = Sender.ASSISTANT,
            status = status
        )

        if (isIncog) {
            val index = incognitoMessagesList.indexOfFirst { it.id == messageId }
            if (index != -1) {
                incognitoMessagesList[index] = updatedMessage
                _messages.value = incognitoMessagesList.toList()
            }
        } else {
            repository.saveMessage(convId, updatedMessage, false)
        }
    }

    fun clearChat() {
        val convId = _currentConversationId.value
        val isIncog = _isIncognito.value
        viewModelScope.launch {
            _pendingAction.value = null
            _attachedImage.value = null
            if (isIncog) {
                incognitoMessagesList.clear()
                _messages.value = emptyList()
                (assistantService as? GeminiAssistantService)?.toolRegistry?.clearIncognitoSessionState()
            } else {
                repository.deleteConversation(convId)
                startNewConversation()
            }
            _isThinking.value = false
            _inputText.value = ""
        }
    }

    private suspend fun handleExplicitCommand(content: String, convId: String, isIncog: Boolean): Boolean {
        if (isIncog) return false
        
        val trimmed = content.trim()
        
        // 1. Remember this:
        if (trimmed.startsWith("Remember this:", ignoreCase = true) || trimmed.startsWith("Remember that", ignoreCase = true)) {
            val prefix = if (trimmed.startsWith("Remember this:", ignoreCase = true)) "Remember this:" else "Remember that"
            val body = trimmed.substring(prefix.length).trim()
            if (body.isNotBlank()) {
                val newMemory = Memory(
                    id = UUID.randomUUID().toString(),
                    content = body,
                    category = "Preferences",
                    createdTimestamp = System.currentTimeMillis(),
                    lastUpdatedTimestamp = System.currentTimeMillis(),
                    sourceConversationId = convId,
                    confidenceScore = 5,
                    state = "CONFIRMED"
                )
                memoryRepository.saveMemory(newMemory)
                postSystemResponse("I have saved that memory for you: \"$body\".", convId)
                return true
            }
        }

        // 2. Don't remember this:
        if (trimmed.startsWith("Don't remember this:", ignoreCase = true) || trimmed.startsWith("Do not remember this:", ignoreCase = true)) {
            postSystemResponse("I will keep this conversation segment fully private and excluded from my long-term memory.", convId)
            return true
        }

        // 3. Forget about:
        if (trimmed.startsWith("Forget about", ignoreCase = true)) {
            val target = trimmed.substring("Forget about".length).trim()
            if (target.isNotBlank()) {
                val active = memoryRepository.getAllActiveMemories().filter { 
                    it.state != "ARCHIVED" && it.state != "SUPERSEDED" && !it.isArchived
                }
                val matches = active.filter { it.content.contains(target, ignoreCase = true) }
                when {
                    matches.isEmpty() -> {
                        postSystemResponse("I could not find any active memories containing \"$target\" to forget.", convId)
                    }
                    matches.size == 1 -> {
                        val match = matches.first()
                        val archived = match.copy(
                            state = "ARCHIVED",
                            isArchived = true,
                            lastUpdatedTimestamp = System.currentTimeMillis()
                        )
                        memoryRepository.saveMemory(archived)
                        postSystemResponse("I have forgotten and archived the memory: \"${match.content}\".", convId)
                    }
                    else -> {
                        val pending = PendingAction(
                            type = PendingActionType.FORGET,
                            originalTarget = target,
                            candidateMemoryIds = matches.map { it.id },
                            displayedCandidates = matches.map { it.content }
                        )
                        _pendingAction.value = pending

                        val sb = StringBuilder("I found multiple memories matching \"$target\". Which one did you want me to forget?\n")
                        matches.forEachIndexed { index, memory ->
                            sb.append("\n${index + 1}. [${memory.category}] ${memory.content}")
                        }
                        postSystemResponse(sb.toString(), convId)
                    }
                }
                return true
            }
        }

        // 4. Delete permanently:
        if (trimmed.startsWith("Delete permanently", ignoreCase = true)) {
            val target = trimmed.substring("Delete permanently".length).trim()
            if (target.isNotBlank()) {
                val active = memoryRepository.getAllActiveMemories().filter {
                    it.state != "ARCHIVED" && it.state != "SUPERSEDED" && !it.isArchived
                }
                val matches = active.filter { it.content.contains(target, ignoreCase = true) }
                when {
                    matches.isEmpty() -> {
                        postSystemResponse("I could not find any active memories containing \"$target\" to permanently delete.", convId)
                    }
                    matches.size == 1 -> {
                        val match = matches.first()
                        memoryRepository.deleteMemoryById(match.id)
                        postSystemResponse("I have permanently erased the memory: \"${match.content}\" from my database.", convId)
                    }
                    else -> {
                        val pending = PendingAction(
                            type = PendingActionType.PERMANENT_DELETE,
                            originalTarget = target,
                            candidateMemoryIds = matches.map { it.id },
                            displayedCandidates = matches.map { it.content }
                        )
                        _pendingAction.value = pending

                        val sb = StringBuilder("I found multiple memories matching \"$target\" for permanent deletion. Which one would you like to delete permanently?\n")
                        matches.forEachIndexed { index, memory ->
                            sb.append("\n${index + 1}. [${memory.category}] ${memory.content}")
                        }
                        postSystemResponse(sb.toString(), convId)
                    }
                }
                return true
            }
        }

        // 5. Update what you remember about X to Y
        if (trimmed.startsWith("Update what you remember about", ignoreCase = true)) {
            val pattern = Regex("(?i)Update what you remember about (.+?) to (.+)")
            val matchResult = pattern.find(trimmed)
            if (matchResult != null) {
                val target = matchResult.groupValues[1].trim()
                val newContent = matchResult.groupValues[2].trim()
                
                val active = memoryRepository.getAllActiveMemories().filter {
                    it.state != "ARCHIVED" && it.state != "SUPERSEDED" && !it.isArchived
                }
                val matches = active.filter { it.content.contains(target, ignoreCase = true) }
                when {
                    matches.isEmpty() -> {
                        postSystemResponse("I could not find any memories containing \"$target\" to update.", convId)
                    }
                    matches.size == 1 -> {
                        val match = matches.first()
                        
                        val superseded = match.copy(
                            state = "SUPERSEDED",
                            lastUpdatedTimestamp = System.currentTimeMillis()
                        )
                        memoryRepository.saveMemory(superseded)
                        
                        val newMemory = Memory(
                            id = UUID.randomUUID().toString(),
                            content = newContent,
                            category = match.category,
                            createdTimestamp = System.currentTimeMillis(),
                            lastUpdatedTimestamp = System.currentTimeMillis(),
                            sourceConversationId = convId,
                            confidenceScore = 5,
                            state = "CONFIRMED"
                        )
                        memoryRepository.saveMemory(newMemory)
                        
                        postSystemResponse("I have updated that memory to: \"$newContent\".", convId)
                    }
                    else -> {
                        val sb = StringBuilder("I found multiple memories matching \"$target\". Which one would you like to update?\n")
                        matches.forEachIndexed { index, memory ->
                            sb.append("\n${index + 1}. [${memory.category}] ${memory.content}")
                        }
                        postSystemResponse(sb.toString(), convId)
                    }
                }
                return true
            }
        }
        
        return false
    }

    private suspend fun postSystemResponse(replyContent: String, convId: String) {
        val assistantMessage = Message(
            id = UUID.randomUUID().toString(),
            content = replyContent,
            sender = Sender.ASSISTANT,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT
        )
        repository.saveMessage(convId, assistantMessage, false)
        
        val allMsgs = repository.getMessagesForConversation(convId).first()
        _messages.value = allMsgs
    }

    private fun isExplicitCommandPattern(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("Remember this:", ignoreCase = true) ||
                trimmed.startsWith("Remember that", ignoreCase = true) ||
                trimmed.startsWith("Don't remember this:", ignoreCase = true) ||
                trimmed.startsWith("Do not remember this:", ignoreCase = true) ||
                trimmed.startsWith("Forget about", ignoreCase = true) ||
                trimmed.startsWith("Delete permanently", ignoreCase = true) ||
                trimmed.startsWith("Update what you remember about", ignoreCase = true)
    }

    private fun isCancellationCommand(input: String): Boolean {
        val clean = input.trim().lowercase()
        return clean == "cancel" || clean == "never mind" || clean == "forget it"
    }

    private fun parseSelectionInput(input: String, maxIndex: Int): Int? {
        val trimmed = input.trim().lowercase()

        val ordinalMap = mapOf(
            "first" to 0,
            "second" to 1,
            "third" to 2,
            "fourth" to 3,
            "fifth" to 4,
            "sixth" to 5,
            "seventh" to 6,
            "eighth" to 7,
            "ninth" to 8,
            "tenth" to 9
        )
        if (ordinalMap.containsKey(trimmed)) {
            val idx = ordinalMap[trimmed]!!
            if (idx < maxIndex) return idx
        }

        var clean = trimmed
        if (clean.startsWith("number")) {
            clean = clean.substring("number".length).trim()
        }
        if (clean.startsWith("#")) {
            clean = clean.substring(1).trim()
        }

        val parsedInt = clean.toIntOrNull()
        if (parsedInt != null) {
            val idx = parsedInt - 1
            if (idx in 0 until maxIndex) {
                return idx
            }
        }

        return null
    }
}
