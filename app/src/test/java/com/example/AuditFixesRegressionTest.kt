package com.example

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.MemoryServiceImpl
import com.example.ai.TimeProvider
import com.example.ai.model.Candidate
import com.example.ai.model.Content
import com.example.ai.model.GenerateContentRequest
import com.example.ai.model.GenerateContentResponse
import com.example.ai.model.Part
import com.example.ai.network.GeminiApiService
import com.example.ai.network.GeminiDynamicApiService
import com.example.ai.network.RetrofitClient
import com.example.ai.proactive.ProactiveAction
import com.example.ai.proactive.ProactiveEngine
import com.example.ai.proactive.ProactiveSignalDetector
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.GeminiProvider
import com.example.ai.provider.selection.GeminiModelVariant
import com.example.ai.provider.selection.ModelSelectionManager
import com.example.ai.tool.ToolRegistry
import com.example.ai.tool.impl.ReminderReceiver
import com.example.ai.tool.impl.RemindersTool
import com.example.data.local.AppDatabase
import com.example.data.model.Memory
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import com.example.util.ApiKeySecurityMode
import com.example.util.ApiKeyStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class AuditFixesRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private class FakeTimeProvider(var nowMillis: Long = 1_750_000_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = nowMillis
        override fun getZoneId(): String = "UTC"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        context.getSharedPreferences("aether_reminders_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("user_api_keys_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // ISSUE 1: Incognito Privacy (AlarmManager & Session-Only Reminders)
    // =========================================================================

    @Test
    fun `issue1 incognito reminder never schedules AlarmManager and clears on session exit`() = runBlocking {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        shadowAlarmManager.scheduledAlarms.clear()

        val remindersTool = RemindersTool(context)
        val createArgs = mapOf<String, Any?>(
            "action" to "create",
            "title" to "Private Incognito Medical Appointment",
            "time" to "15",
            "is_incognito" to true
        )

        val result = remindersTool.execute(createArgs)
        assertTrue(result.content.contains("\"session_only\":true"))
        assertTrue(result.content.contains("\"alarm_scheduled\":false"))

        // Verify NO system AlarmManager alarm was scheduled in Incognito
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)

        // Verify nothing was written to persistent Room database
        val dbReminders = AppDatabase.getDatabase(context).reminderDao().getAll()
        assertTrue(dbReminders.none { it.title == "Private Incognito Medical Appointment" })

        // Verify incognito reminder exists only in transient memory during the session
        val listBeforeClear = remindersTool.execute(
            mapOf("action" to "list", "is_incognito" to true)
        )
        assertTrue(listBeforeClear.content.contains("Private Incognito Medical Appointment"))

        // Verify clearing incognito session state wipes transient reminders
        val registry = ToolRegistry(context, registerDefaults = true)
        val regReminders = registry.get("manage_reminders") as RemindersTool
        regReminders.execute(createArgs)
        registry.clearIncognitoSessionState()

        val listAfterClear = regReminders.execute(
            mapOf("action" to "list", "is_incognito" to true)
        )
        assertFalse(listAfterClear.content.contains("Private Incognito Medical Appointment"))
        assertTrue(listAfterClear.content.contains("\"count\":0"))
    }

    @Test
    fun `issue1 ReminderReceiver drops intent if is_incognito flag is present`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)
        shadowNotificationManager.allNotifications.clear()

        val receiver = ReminderReceiver()
        val incognitoIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_id", "rem_incog_1")
            putExtra("reminder_title", "Top Secret Incognito Reminder")
            putExtra("is_incognito", true)
        }

        receiver.onReceive(context, incognitoIntent)
        assertEquals(0, shadowNotificationManager.allNotifications.size)
    }

    // =========================================================================
    // ISSUE 2: Official Gemini Model IDs & Fallback Active Model Disclosure
    // =========================================================================

    @Test
    fun `issue2 official Gemini 3_8 Flash and 3_1 Pro model IDs and preference migration`() {
        assertEquals("gemini-3.8-flash", GeminiProvider.DEFAULT_MODEL_ID)
        assertEquals("gemini-3.1-pro-preview", GeminiProvider.PRO_MODEL_ID)
        assertEquals("gemini-3.5-flash", GeminiProvider.LIVE_VERIFIED_FALLBACK_ID)

        // Nonexistent 3.8 Pro must NEVER exist in catalog
        assertFalse(GeminiProvider.OFFICIAL_MODEL_CATALOG.contains("gemini-3.8-pro"))

        // Simulate legacy "PRO_3_8" saved in SharedPreferences before upgrade
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_model_variant", "PRO_3_8").commit()

        val manager = ModelSelectionManager(context)
        val migratedVariant = manager.getGeminiModelVariant(isIncognito = false)
        assertEquals(GeminiModelVariant.PRO_3_1, migratedVariant)
        assertEquals("gemini-3.1-pro-preview", migratedVariant.modelId)

        // Check metadata registry models
        val models = manager.metadataRegistry.getModels(AIProviderType.GEMINI).map { it.modelId }
        assertTrue(models.contains("gemini-3.8-flash"))
        assertTrue(models.contains("gemini-3.1-pro-preview"))
        assertTrue(models.contains("gemini-3.5-flash"))
        assertFalse(models.contains("gemini-3.8-pro"))
    }

    @Test
    fun `issue2 GeminiProvider falls back from 3_1 Pro on 404 to 3_8 Flash and discloses active fallback model`() = runBlocking {
        val requestedModels = mutableListOf<String>()

        val fakeDynamicService = object : GeminiDynamicApiService {
            override suspend fun generateContent(
                model: String,
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                requestedModels.add(model)
                if (model == "gemini-3.1-pro-preview") {
                    throw HttpException(
                        Response.error<GenerateContentResponse>(
                            404,
                            "{\"error\":\"model not found\"}".toResponseBody("application/json".toMediaType())
                        )
                    )
                }
                return GenerateContentResponse(
                    candidates = listOf(
                        Candidate(content = Content(role = "model", parts = listOf(Part(text = "Fallback response from $model"))))
                    )
                )
            }

            override suspend fun generateContentStream(
                model: String,
                apiKey: String,
                request: GenerateContentRequest
            ): ResponseBody {
                requestedModels.add(model)
                if (model == "gemini-3.1-pro-preview") {
                    throw HttpException(
                        Response.error<ResponseBody>(
                            404,
                            "{\"error\":\"model not found\"}".toResponseBody("application/json".toMediaType())
                        )
                    )
                }
                val sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Streamed from $model\"}]}}]}\n\n"
                return sse.toResponseBody("text/event-stream".toMediaType())
            }
        }

        val geminiProvider = GeminiProvider(
            timeProvider = FakeTimeProvider(),
            apiKeyProvider = { "test_valid_key" },
            dynamicApiService = fakeDynamicService
        )

        val assistantService = GeminiAssistantService(
            timeProvider = FakeTimeProvider(),
            selectionManager = ModelSelectionManager(context),
            provider = geminiProvider
        )

        val chunks = assistantService.generateResponseStream(
            userMessage = "Explain fallback disclosure",
            history = emptyList(),
            isIncognito = false,
            preferredModelId = "gemini-3.1-pro-preview"
        ).toList()

        assertTrue(chunks.last().contains("Streamed from gemini-3.8-flash"))
        assertEquals(listOf("gemini-3.1-pro-preview", "gemini-3.8-flash"), requestedModels)
        assertEquals("gemini-3.8-flash", geminiProvider.lastActiveModelId)
        assertTrue(geminiProvider.lastCallUsedFallback)

        val disclosedModel = assistantService.lastUsedModelFlow.first()
        assertEquals("gemini-3.8-flash (Fallback)", disclosedModel)
    }

    @Test
    fun `issue2 Gemini 3_8 Flash and 3_1 Pro successful direct requests without fallback`() = runBlocking {
        val requestedModels = mutableListOf<String>()

        val fakeDynamicService = object : GeminiDynamicApiService {
            override suspend fun generateContent(
                model: String,
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                requestedModels.add(model)
                return GenerateContentResponse(
                    candidates = listOf(
                        Candidate(content = Content(role = "model", parts = listOf(Part(text = "Response from $model"))))
                    )
                )
            }

            override suspend fun generateContentStream(
                model: String,
                apiKey: String,
                request: GenerateContentRequest
            ): ResponseBody {
                requestedModels.add(model)
                val sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Streamed directly from $model\"}]}}]}\n\n"
                return sse.toResponseBody("text/event-stream".toMediaType())
            }
        }

        val geminiProvider = GeminiProvider(
            timeProvider = FakeTimeProvider(),
            apiKeyProvider = { "test_valid_key" },
            dynamicApiService = fakeDynamicService
        )

        val assistantService = GeminiAssistantService(
            timeProvider = FakeTimeProvider(),
            selectionManager = ModelSelectionManager(context),
            provider = geminiProvider
        )

        // 1. Direct call with Gemini 3.8 Flash
        val flashChunks = assistantService.generateResponseStream(
            userMessage = "Hello 3.8 Flash",
            history = emptyList(),
            isIncognito = false,
            preferredModelId = "gemini-3.8-flash"
        ).toList()
        assertTrue(flashChunks.last().contains("Streamed directly from gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", geminiProvider.lastActiveModelId)
        assertFalse(geminiProvider.lastCallUsedFallback)
        assertEquals("gemini-3.8-flash", assistantService.lastUsedModelFlow.first())

        // 2. Direct call with Gemini 3.1 Pro Preview
        val proChunks = assistantService.generateResponseStream(
            userMessage = "Hello 3.1 Pro",
            history = emptyList(),
            isIncognito = false,
            preferredModelId = "gemini-3.1-pro-preview"
        ).toList()
        assertTrue(proChunks.last().contains("Streamed directly from gemini-3.1-pro-preview"))
        assertEquals("gemini-3.1-pro-preview", geminiProvider.lastActiveModelId)
        assertFalse(geminiProvider.lastCallUsedFallback)
        assertEquals("gemini-3.1-pro-preview", assistantService.lastUsedModelFlow.first())
    }

    @Test
    fun `issue2 GeminiProvider falls back on timeout or 503 to verified 3_5 Flash and handles invalid model ID`() = runBlocking {
        val requestedModels = mutableListOf<String>()

        val fakeDynamicService = object : GeminiDynamicApiService {
            override suspend fun generateContent(
                model: String,
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                requestedModels.add(model)
                if (model == "gemini-3.8-flash") {
                    throw java.net.SocketTimeoutException("Gateway connection timed out")
                }
                return GenerateContentResponse(
                    candidates = listOf(
                        Candidate(content = Content(role = "model", parts = listOf(Part(text = "Handled by $model"))))
                    )
                )
            }

            override suspend fun generateContentStream(
                model: String,
                apiKey: String,
                request: GenerateContentRequest
            ): ResponseBody {
                requestedModels.add(model)
                if (model == "gemini-3.8-flash") {
                    throw java.net.SocketTimeoutException("Gateway connection timed out")
                }
                val sse = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Streamed via $model\"}]}}]}\n\n"
                return sse.toResponseBody("text/event-stream".toMediaType())
            }
        }

        val geminiProvider = GeminiProvider(
            timeProvider = FakeTimeProvider(),
            apiKeyProvider = { "test_valid_key" },
            dynamicApiService = fakeDynamicService
        )

        val assistantService = GeminiAssistantService(
            timeProvider = FakeTimeProvider(),
            selectionManager = ModelSelectionManager(context),
            provider = geminiProvider
        )

        // Case 1: Timeout on gemini-3.8-flash triggers fallback to verified gemini-3.5-flash
        val chunksFallback = assistantService.generateResponseStream(
            userMessage = "Test timeout fallback",
            history = emptyList(),
            isIncognito = false,
            preferredModelId = "gemini-3.8-flash"
        ).toList()
        assertTrue(chunksFallback.last().contains("Streamed via gemini-3.5-flash"))
        assertTrue(geminiProvider.lastCallUsedFallback)
        assertEquals("gemini-3.5-flash (Fallback)", assistantService.lastUsedModelFlow.first())

        // Case 2: Requesting an invalid / nonexistent model ID e.g. "gemini-3.8-pro" normalizes to 3.1 Pro or default and discloses fallback
        val chunksInvalid = assistantService.generateResponseStream(
            userMessage = "Test invalid model ID",
            history = emptyList(),
            isIncognito = false,
            preferredModelId = "gemini-unknown-model"
        ).toList()
        // Invalid model normalizes to primary (3.8 Flash), which times out, then falls back to 3.5 Flash
        assertTrue(chunksInvalid.last().contains("Streamed via gemini-3.5-flash"))
        assertTrue(geminiProvider.lastCallUsedFallback)
        assertEquals("gemini-3.5-flash (Fallback)", assistantService.lastUsedModelFlow.first())
    }

    // =========================================================================
    // ISSUE 3: Memory Safety (Extract Only From User-Authored Messages)
    // =========================================================================

    @Test
    fun `issue3 memory extraction only sends user-authored messages and strips assistant claims`() = runBlocking {
        var capturedPrompt = ""
        val fakeApiService = object : GeminiApiService {
            override suspend fun generateContent(
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                capturedPrompt = request.contents.firstOrNull()?.parts?.firstOrNull()?.text.orEmpty()
                val deltaJson = """
                    [
                      {
                        "action": "ADD",
                        "category": "Personal",
                        "content": "User is a software engineer living in Bengaluru.",
                        "temporalType": "PERSISTENT",
                        "expirationDate": null
                      },
                      {
                        "action": "ADD",
                        "category": "Personal",
                        "content": "Assistant assumed user also works at SpaceX in Mars Colony.",
                        "temporalType": "PERSISTENT",
                        "expirationDate": null
                      }
                    ]
                """.trimIndent()
                return GenerateContentResponse(
                    candidates = listOf(
                        Candidate(content = Content(role = "model", parts = listOf(Part(text = deltaJson))))
                    )
                )
            }

            override suspend fun generateContentStream(
                apiKey: String,
                request: GenerateContentRequest
            ): ResponseBody {
                return "".toResponseBody()
            }
        }

        val memoryService = MemoryServiceImpl(
            timeProvider = FakeTimeProvider(),
            apiService = fakeApiService,
            apiKeyProvider = { "test_valid_key" }
        )

        val conversationMessages = listOf(
            Message(
                id = "m1",
                content = "I am a software engineer living in Bengaluru.",
                sender = Sender.USER,
                status = MessageStatus.SENT
            ),
            Message(
                id = "m2",
                content = "Since you live in Tokyo and work at SpaceX, you must love rockets!",
                sender = Sender.ASSISTANT,
                status = MessageStatus.SENT
            )
        )

        val deltas = memoryService.extractMemoryDeltas("conv_1", conversationMessages, emptyList())

        // 1. Prompt sent to LLM must include user message and MUST NOT include assistant message
        assertTrue(capturedPrompt.contains("I am a software engineer living in Bengaluru."))
        assertFalse(capturedPrompt.contains("Since you live in Tokyo"))
        assertFalse(capturedPrompt.contains("Assistant:"))

        // 2. Defense-in-depth filter rejects deltas attributing facts to the assistant
        assertEquals(1, deltas.size)
        assertEquals("User is a software engineer living in Bengaluru.", deltas.first().content)
    }

    // =========================================================================
    // ISSUE 4: Proactive Cooldowns Persisted in Room Across App Restarts
    // =========================================================================

    @Test
    fun `issue4 proactive cooldowns persist in Room across ProactiveEngine restarts`() = runBlocking {
        val fakeTime = FakeTimeProvider(nowMillis = 1_750_000_000_000L)
        val cooldownDao = db.proactiveCooldownDao()
        val detector = ProactiveSignalDetector(fakeTime)

        val upcomingMemory = Memory(
            id = "mem_interview_1",
            content = "User has a system design interview tomorrow regarding distributed databases",
            category = "Events",
            createdTimestamp = fakeTime.nowMillis - 3_600_000L,
            lastUpdatedTimestamp = fakeTime.nowMillis - 3_600_000L,
            sourceConversationId = "conv_1",
            confidenceScore = 5,
            state = "CONFIRMED",
            temporalType = "TEMPORARY_EVENT",
            expirationTimestamp = fakeTime.nowMillis + 48 * 3_600_000L // 48 hours away
        )

        val signals = detector.detectSignals(
            currentContext = "Let's review distributed databases for my system design interview",
            isIncognito = false,
            memories = listOf(upcomingMemory)
        )
        assertTrue(signals.isNotEmpty())
        val signal = signals.first()

        // First engine instance marks memory as surfaced
        val engineBeforeRestart = ProactiveEngine(
            timeProvider = fakeTime,
            cooldownDaoProvider = { cooldownDao }
        )
        val decision1 = engineBeforeRestart.determineDecisionForSignals(
            currentContext = "Let's review distributed databases for my system design interview",
            isIncognito = false,
            signals = listOf(signal)
        )
        assertEquals(ProactiveAction.PROACT, decision1.decision)
        engineBeforeRestart.markAsSurfaced(signal.sourceMemoryId ?: signal.id)

        // Verify Room row exists
        val persistedCooldown = cooldownDao.getCooldown("mem_interview_1")
        assertNotNull(persistedCooldown)
        assertEquals(fakeTime.nowMillis, persistedCooldown?.lastSurfacedTimestamp)

        // Advance time by 2 minutes (< 5m default cooldownDurationMs) and create a brand-new ProactiveEngine (simulating app restart)
        fakeTime.nowMillis += 120_000L
        val engineAfterRestart = ProactiveEngine(
            timeProvider = fakeTime,
            cooldownDaoProvider = { cooldownDao }
        )
        val decisionAfterRestart = engineAfterRestart.determineDecisionForSignals(
            currentContext = "Let's review distributed databases for my system design interview",
            isIncognito = false,
            signals = listOf(signal)
        )
        assertEquals(ProactiveAction.STAY_SILENT, decisionAfterRestart.decision)
        assertTrue(decisionAfterRestart.reason.contains("cooldown", ignoreCase = true))

        // Advance time past 5-minute cooldown (add 4 more minutes = 6 minutes total); cooldown should now expire and allow surfacing
        fakeTime.nowMillis += 240_000L
        val decisionAfterExpiry = engineAfterRestart.determineDecisionForSignals(
            currentContext = "Let's review distributed databases for my system design interview",
            isIncognito = false,
            signals = listOf(signal)
        )
        assertEquals(ProactiveAction.PROACT, decisionAfterExpiry.decision)
    }

    @Test
    fun `issue4 proactive cooldowns persist across database close and reopen on disk`(): Unit = runBlocking {
        val testDbName = "disk_cooldown_test.db"
        context.deleteDatabase(testDbName)
        val fakeTime = FakeTimeProvider(nowMillis = 1_750_000_000_000L)

        // 1. Open disk database and mark memory as surfaced
        var diskDb = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

        val engine1 = ProactiveEngine(
            timeProvider = fakeTime,
            cooldownDaoProvider = { diskDb.proactiveCooldownDao() }
        )
        engine1.markAsSurfaced("signal_disk_test_1")

        // 2. Close disk database completely
        diskDb.close()

        // 3. Reopen new database connection from disk
        val reopenedDb = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

        val engine2 = ProactiveEngine(
            timeProvider = fakeTime,
            cooldownDaoProvider = { reopenedDb.proactiveCooldownDao() }
        )

        val detector = ProactiveSignalDetector(fakeTime)
        val memory = Memory(
            id = "signal_disk_test_1",
            content = "User has an important flight departure tomorrow afternoon",
            category = "Travel",
            createdTimestamp = fakeTime.nowMillis - 3_600_000L,
            lastUpdatedTimestamp = fakeTime.nowMillis - 3_600_000L,
            sourceConversationId = "conv_travel",
            confidenceScore = 5,
            state = "CONFIRMED",
            temporalType = "TEMPORARY_EVENT",
            expirationTimestamp = fakeTime.nowMillis + 86_400_000L
        )

        val signals = detector.detectSignals(
            currentContext = "Check flights and travel details",
            isIncognito = false,
            memories = listOf(memory)
        )
        assertTrue(signals.isNotEmpty())

        // 4. Assert engine2 suppresses suggestion due to persisted cooldown in reopened DB
        val decisionSuppressed = engine2.determineDecisionForSignals(
            currentContext = "Check flights and travel details",
            isIncognito = false,
            signals = signals
        )
        assertEquals(ProactiveAction.STAY_SILENT, decisionSuppressed.decision)
        assertTrue(decisionSuppressed.reason.contains("cooldown", ignoreCase = true))

        reopenedDb.close()
        context.deleteDatabase(testDbName)
        Unit
    }

    @Test
    fun `issue1 end-to-end incognito session never persists to disk database across restart`(): Unit = runBlocking {
        val testDbName = "disk_incognito_test.db"
        context.deleteDatabase(testDbName)

        val diskDb = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

        val remindersTool = RemindersTool(context)

        // Create incognito reminder
        val res = remindersTool.execute(
            mapOf(
                "action" to "create",
                "title" to "Secret Medical Meeting",
                "time" to "30",
                "is_incognito" to true
            )
        )
        assertTrue(res.content.contains("\"session_only\":true"))

        // Check tool registry session cleanup
        val registry = ToolRegistry(context, registerDefaults = true)
        val regReminders = registry.get("manage_reminders") as RemindersTool
        regReminders.execute(
            mapOf(
                "action" to "create",
                "title" to "Secret Consultation",
                "time" to "45",
                "is_incognito" to true
            )
        )
        registry.clearIncognitoSessionState()

        val afterClearList = regReminders.execute(
            mapOf("action" to "list", "is_incognito" to true)
        )
        assertTrue(afterClearList.content.contains("\"count\":0"))

        // Close and reopen database
        diskDb.close()
        val reopenedDb = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

        // Verify zero incognito reminders exist in persistent Room database
        val persistentReminders = reopenedDb.reminderDao().getAll()
        assertEquals(0, persistentReminders.size)

        reopenedDb.close()
        context.deleteDatabase(testDbName)
        Unit
    }

    // =========================================================================
    // ISSUE 5: API Key Security Audit & Backend Proxy Request Rewriting
    // =========================================================================

    @Test
    fun `issue5 ApiKeyStorage audit and RetrofitClient proxy and header rewriting`() {
        // 1. Direct request rewrites ?key= into x-goog-api-key header so URL never exposes secret
        val originalDirectRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=AIzaTestKey123")
            .build()

        val rewrittenDirect = RetrofitClient.rewriteRequestForSecurityAndProxy(
            original = originalDirectRequest,
            proxyBaseUrl = "",
            proxyAuthToken = ""
        )
        assertEquals(null, rewrittenDirect.url.queryParameter("key"))
        assertEquals("AIzaTestKey123", rewrittenDirect.header("x-goog-api-key"))

        // 2. When backend proxy is configured, auditSecurity reports BACKEND_PROXY and requests route to proxy without key in URL
        ApiKeyStorage.saveGeminiProxyBaseUrl(context, "https://proxy.aether.example.com/api")
        ApiKeyStorage.saveProxyAuthToken(context, "session_jwt_token_xyz")

        val audit = ApiKeyStorage.auditSecurity(context)
        assertEquals(ApiKeySecurityMode.BACKEND_PROXY, audit.mode)
        assertTrue(audit.usesBackendProxy)
        assertTrue(ApiKeyStorage.isProviderConfigured(AIProviderType.GEMINI, context))

        val proxyRequestInput = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=${ApiKeyStorage.PROXY_SENTINEL_KEY}")
            .build()

        val rewrittenProxy = RetrofitClient.rewriteRequestForSecurityAndProxy(
            original = proxyRequestInput,
            proxyBaseUrl = ApiKeyStorage.getGeminiProxyBaseUrl(context),
            proxyAuthToken = ApiKeyStorage.getProxyAuthToken(context)
        )
        assertEquals("proxy.aether.example.com", rewrittenProxy.url.host)
        assertEquals("/api/v1beta/models/gemini-2.5-flash:streamGenerateContent", rewrittenProxy.url.encodedPath)
        assertEquals("sse", rewrittenProxy.url.queryParameter("alt"))
        assertEquals(null, rewrittenProxy.url.queryParameter("key"))
        assertEquals("Bearer session_jwt_token_xyz", rewrittenProxy.header("Authorization"))
        assertEquals(null, rewrittenProxy.header("x-goog-api-key"))
    }
}
