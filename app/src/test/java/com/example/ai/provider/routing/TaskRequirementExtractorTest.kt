package com.example.ai.provider.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderRole
import com.example.data.model.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskRequirementExtractorTest {

    private lateinit var context: Context
    private lateinit var extractor: TaskRequirementExtractor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        extractor = DefaultTaskRequirementExtractor()
    }

    @Test
    fun testEmptyAndBasicRequest() {
        val emptyRequest = ProviderRequest(userMessage = "")
        val emptyProfile = extractor.extract(emptyRequest)

        assertEquals(0, emptyProfile.estimatedInputTokens)
        assertFalse(emptyProfile.requiresLongContext)
        assertFalse(emptyProfile.requiresToolCalling)
        assertFalse(emptyProfile.requiresVision)
        assertFalse(emptyProfile.requiresWebSearch)
        assertNull(emptyProfile.userPreferredProvider)

        val basicRequest = ProviderRequest(userMessage = "Hello world")
        val basicProfile = extractor.extract(basicRequest)

        // 11 characters / 4 = 2 tokens (at least 1)
        assertEquals(2, basicProfile.estimatedInputTokens)
        assertFalse(basicProfile.requiresLongContext)
        assertFalse(basicProfile.requiresToolCalling)
        assertFalse(basicProfile.requiresVision)
        assertFalse(basicProfile.requiresWebSearch)
        assertNull(basicProfile.userPreferredProvider)
    }

    @Test
    fun testExplicitToolCallingRequirement() {
        val reqWithoutTool = ProviderRequest(
            userMessage = "Calculate 2 + 2",
            requiresToolCalling = false
        )
        assertFalse(extractor.extract(reqWithoutTool).requiresToolCalling)

        val reqWithTool = ProviderRequest(
            userMessage = "Calculate 2 + 2",
            requiresToolCalling = true
        )
        val profile = extractor.extract(reqWithTool)
        assertTrue(profile.requiresToolCalling)
    }

    @Test
    fun testExplicitVisionRequirement() {
        val reqWithoutVision = ProviderRequest(
            userMessage = "What do you see?",
            hasVisionInput = false
        )
        assertFalse(extractor.extract(reqWithoutVision).requiresVision)

        val reqWithVision = ProviderRequest(
            userMessage = "What do you see?",
            hasVisionInput = true
        )
        assertTrue(extractor.extract(reqWithVision).requiresVision)
    }

    @Test
    fun testExplicitWebSearchRequirement() {
        val reqWithoutSearch = ProviderRequest(
            userMessage = "Find current weather",
            requiresWebSearch = false
        )
        assertFalse(extractor.extract(reqWithoutSearch).requiresWebSearch)

        val reqWithSearch = ProviderRequest(
            userMessage = "Find current weather",
            requiresWebSearch = true
        )
        assertTrue(extractor.extract(reqWithSearch).requiresWebSearch)
    }

    @Test
    fun testFileContextDoesNotImplyToolCalling() {
        val reqWithFile = ProviderRequest(
            userMessage = "Summarize this attached report",
            fileContext = "Quarterly Report 2026: Revenue grew by 15% across all core sectors.",
            requiresToolCalling = false
        )
        val profile = extractor.extract(reqWithFile)

        assertFalse(profile.requiresToolCalling)
        assertTrue(profile.estimatedInputTokens!! > 0)
    }

    @Test
    fun testMemoryContextDoesNotImplyToolCalling() {
        val memory = Memory(
            id = "mem-1",
            content = "User prefers light mode and concise answers",
            category = "preference",
            createdTimestamp = 1000L,
            lastUpdatedTimestamp = 1000L,
            sourceConversationId = null
        )
        val reqWithMemory = ProviderRequest(
            userMessage = "What are best practices for Kotlin coroutines?",
            relevantMemories = listOf(memory),
            requiresToolCalling = false
        )
        val profile = extractor.extract(reqWithMemory)

        assertFalse(profile.requiresToolCalling)
        assertTrue(profile.estimatedInputTokens!! > 0)
    }

    @Test
    fun testTokenEstimationIsDeterministic() {
        val request = ProviderRequest(
            userMessage = "A".repeat(400),
            history = listOf(
                ProviderMessage(ProviderRole.USER, "B".repeat(200)),
                ProviderMessage(ProviderRole.ASSISTANT, "C".repeat(200))
            ),
            fileContext = "D".repeat(400),
            relevantMemories = listOf(
                Memory(
                    id = "m1",
                    content = "E".repeat(400),
                    category = "general",
                    createdTimestamp = 1000L,
                    lastUpdatedTimestamp = 1000L,
                    sourceConversationId = null
                )
            )
        )

        // Total chars = 400 + 200 + 200 + 400 + 400 = 1600 chars.
        // Expected tokens = 1600 / 4 = 400 tokens.
        val profile1 = extractor.extract(request)
        val profile2 = extractor.extract(request)

        assertEquals(400, profile1.estimatedInputTokens)
        assertEquals(400, profile2.estimatedInputTokens)
        assertEquals(profile1.estimatedInputTokens, profile2.estimatedInputTokens)
    }

    @Test
    fun testLongContextThresholdBehavior() {
        // Below threshold (8,000 tokens = 32,000 chars)
        val standardRequest = ProviderRequest(
            userMessage = "X".repeat(30000) // 30,000 chars = 7,500 tokens
        )
        val standardProfile = extractor.extract(standardRequest)
        assertEquals(7500, standardProfile.estimatedInputTokens)
        assertFalse(standardProfile.requiresLongContext)

        // Above threshold (> 8,000 tokens)
        val longRequest = ProviderRequest(
            userMessage = "X".repeat(35000) // 35,000 chars = 8,750 tokens
        )
        val longProfile = extractor.extract(longRequest)
        assertEquals(8750, longProfile.estimatedInputTokens)
        assertTrue(longProfile.requiresLongContext)
    }

    @Test
    fun testVisionIsNotInferredFromOrdinaryText() {
        val promptsWithVisualWords = listOf(
            "Show me an image of a cat",
            "Can you draw a photo of sunset?",
            "Look at this picture carefully",
            "Take a screenshot and explain"
        )

        for (prompt in promptsWithVisualWords) {
            val request = ProviderRequest(
                userMessage = prompt,
                hasVisionInput = false
            )
            val profile = extractor.extract(request)
            assertFalse(
                "Prompt '$prompt' must NOT infer requiresVision=true from text keywords",
                profile.requiresVision
            )
        }
    }

    @Test
    fun testWebSearchIsNotInferredFromOrdinaryText() {
        val promptsWithSearchWords = listOf(
            "Search the web for news",
            "What is the latest score online?",
            "Google this topic for me",
            "Check current internet news"
        )

        for (prompt in promptsWithSearchWords) {
            val request = ProviderRequest(
                userMessage = prompt,
                requiresWebSearch = false
            )
            val profile = extractor.extract(request)
            assertFalse(
                "Prompt '$prompt' must NOT infer requiresWebSearch=true from text keywords",
                profile.requiresWebSearch
            )
        }
    }

    @Test
    fun testExplicitFlagsTakePriority() {
        val explicitRequest = ProviderRequest(
            userMessage = "Do not use tools, no images, no search",
            requiresToolCalling = true,
            hasVisionInput = true,
            requiresWebSearch = true,
            userPreferredProvider = AIProviderType.GROQ
        )

        val profile = extractor.extract(explicitRequest)

        // Explicit structured flags must be strictly preserved
        assertTrue(profile.requiresToolCalling)
        assertTrue(profile.requiresVision)
        assertTrue(profile.requiresWebSearch)
        assertEquals(AIProviderType.GROQ, profile.userPreferredProvider)
    }

    @Test
    fun testSameInputProducesIdenticalProfile() {
        val request = ProviderRequest(
            userMessage = "Complex test query",
            history = listOf(
                ProviderMessage(ProviderRole.USER, "Prior user query"),
                ProviderMessage(ProviderRole.ASSISTANT, "Prior assistant response")
            ),
            fileContext = "File snippet with code",
            requiresToolCalling = false,
            hasVisionInput = false,
            requiresWebSearch = false,
            userPreferredProvider = AIProviderType.OPENROUTER
        )

        val profile1 = extractor.extract(request)
        val profile2 = extractor.extract(request)

        assertEquals(profile1.requiresLongContext, profile2.requiresLongContext)
        assertEquals(profile1.requiresToolCalling, profile2.requiresToolCalling)
        assertEquals(profile1.requiresVision, profile2.requiresVision)
        assertEquals(profile1.requiresWebSearch, profile2.requiresWebSearch)
        assertEquals(profile1.estimatedInputTokens, profile2.estimatedInputTokens)
        assertEquals(profile1.userPreferredProvider, profile2.userPreferredProvider)
        assertEquals(profile1, profile2)
    }

    @Test
    fun testNoPersistenceOrSideEffects() {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val request = ProviderRequest(
            userMessage = "Testing side effects",
            requiresToolCalling = true,
            hasVisionInput = true,
            requiresWebSearch = true
        )

        extractor.extract(request)
        extractor.extract(request)

        // Extractor is completely pure and must not persist anything to SharedPreferences
        assertTrue(prefs.all.isEmpty())
    }
}
