package com.example.ai.intent

import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskIntentClassifierTest {

    private lateinit var classifier: TaskIntentClassifier

    @Before
    fun setUp() {
        classifier = DefaultTaskIntentClassifier()
    }

    @Test
    fun testExplicitWebSearchRequirement() {
        val request = ProviderRequest(
            userMessage = "What is the capital of France?",
            requiresWebSearch = true
        )
        val result = classifier.classify(request)

        assertEquals(TaskIntent.WEB_RESEARCH, result.primaryIntent)
        assertEquals(1.0f, result.confidence, 0.001f)
        assertTrue(result.reasoningSignals.contains("EXPLICIT_WEB_SEARCH"))
    }

    @Test
    fun testExplicitVisionInput() {
        val request = ProviderRequest(
            userMessage = "Explain what is shown here",
            hasVisionInput = true
        )
        val result = classifier.classify(request)

        assertEquals(TaskIntent.VISION_ANALYSIS, result.primaryIntent)
        assertEquals(1.0f, result.confidence, 0.001f)
        assertTrue(result.reasoningSignals.contains("EXPLICIT_VISION_INPUT"))
    }

    @Test
    fun testExplicitToolRequirement() {
        // Explicit tool with math message
        val mathToolReq = ProviderRequest(
            userMessage = "calculate 42 * 17",
            requiresToolCalling = true
        )
        val mathResult = classifier.classify(mathToolReq)
        assertEquals(TaskIntent.CALCULATION, mathResult.primaryIntent)
        assertEquals(1.0f, mathResult.confidence, 0.001f)
        assertTrue(mathResult.reasoningSignals.contains("EXPLICIT_TOOL_CALLING"))

        // Explicit tool with generic task
        val genericToolReq = ProviderRequest(
            userMessage = "do something for me",
            requiresToolCalling = true
        )
        val genericResult = classifier.classify(genericToolReq)
        assertEquals(TaskIntent.PRODUCTIVITY, genericResult.primaryIntent)
        assertEquals(1.0f, genericResult.confidence, 0.001f)
        assertTrue(genericResult.reasoningSignals.contains("EXPLICIT_TOOL_CALLING"))
    }

    @Test
    fun testOrdinaryConversation() {
        val greetings = listOf(
            "Hello there!",
            "Hi",
            "Good morning!",
            "How are you?",
            "Thanks a lot!"
        )

        for (text in greetings) {
            val req = ProviderRequest(userMessage = text)
            val result = classifier.classify(req)
            assertEquals("Expected GENERAL_CHAT for '$text'", TaskIntent.GENERAL_CHAT, result.primaryIntent)
            assertTrue(result.confidence >= 0.8f)
        }
    }

    @Test
    fun testAmbiguousRequestReturnsUnknown() {
        val ambiguousPrompts = listOf(
            "maybe",
            "well...",
            "somewhere out there",
            "asdfghjkl",
            "so anyway",
            ""
        )

        for (text in ambiguousPrompts) {
            val req = ProviderRequest(userMessage = text)
            val result = classifier.classify(req)
            assertEquals("Expected UNKNOWN for ambiguous text '$text'", TaskIntent.UNKNOWN, result.primaryIntent)
            assertTrue("Expected low confidence for '$text'", result.confidence < 0.3f)
        }
    }

    @Test
    fun testFileContextWithoutAutomaticallyAssumingToolCalling() {
        val req = ProviderRequest(
            userMessage = "Here is the quarterly metrics file",
            fileContext = "Revenue: $1.2M, Growth: 14%",
            requiresToolCalling = false
        )
        val result = classifier.classify(req)

        // Classifies as FILE_ANALYSIS
        assertEquals(TaskIntent.FILE_ANALYSIS, result.primaryIntent)
        assertEquals(0.95f, result.confidence, 0.001f)
        assertTrue(result.reasoningSignals.contains("STRUCTURED_FILE_ATTACHMENT"))

        // Does NOT assume tool calling
        assertFalse(result.reasoningSignals.contains("EXPLICIT_TOOL_CALLING"))
    }

    @Test
    fun testMultipleStructuredSignals() {
        // Vision + File context
        val visionAndFileReq = ProviderRequest(
            userMessage = "Compare this image with the attached report",
            hasVisionInput = true,
            fileContext = "Report details...",
            requiresToolCalling = false
        )
        val result = classifier.classify(visionAndFileReq)

        assertEquals(TaskIntent.VISION_ANALYSIS, result.primaryIntent)
        assertTrue(result.secondaryIntents.contains(TaskIntent.FILE_ANALYSIS))
        assertEquals(1.0f, result.confidence, 0.001f)
        assertTrue(result.reasoningSignals.contains("EXPLICIT_VISION_INPUT"))
        assertTrue(result.reasoningSignals.contains("STRUCTURED_FILE_ATTACHMENT"))
    }

    @Test
    fun testConfidenceBehavior() {
        // Explicit signals: 1.0f
        val explicitReq = ProviderRequest(userMessage = "search", requiresWebSearch = true)
        assertEquals(1.0f, classifier.classify(explicitReq).confidence, 0.001f)

        // Strong pattern (math): >= 0.90f
        val mathReq = ProviderRequest(userMessage = "125 * 8")
        val mathRes = classifier.classify(mathReq)
        assertEquals(TaskIntent.CALCULATION, mathRes.primaryIntent)
        assertTrue(mathRes.confidence >= 0.90f)

        // Knowledge query: ~0.80f
        val knowReq = ProviderRequest(userMessage = "What is quantum computing?")
        val knowRes = classifier.classify(knowReq)
        assertEquals(TaskIntent.KNOWLEDGE_QUESTION, knowRes.primaryIntent)
        assertTrue(knowRes.confidence in 0.75f..0.85f)

        // Ambiguous: <= 0.20f
        val ambReq = ProviderRequest(userMessage = "not sure")
        val ambRes = classifier.classify(ambReq)
        assertEquals(TaskIntent.UNKNOWN, ambRes.primaryIntent)
        assertTrue(ambRes.confidence <= 0.20f)
    }

    @Test
    fun testVisionNotInferFromWordsAlone() {
        val visualWordsPrompts = listOf(
            "Draw an image of a mountain",
            "I want a photo of a dog",
            "Look at the picture in your mind",
            "Take a snapshot of this idea"
        )

        for (prompt in visualWordsPrompts) {
            val req = ProviderRequest(userMessage = prompt, hasVisionInput = false)
            val result = classifier.classify(req)
            assertNotEquals("Prompt '$prompt' must NOT classify as VISION_ANALYSIS without structured signal",
                TaskIntent.VISION_ANALYSIS, result.primaryIntent)
        }
    }

    @Test
    fun testWebSearchNotInferFromWordsAlone() {
        val searchWordsPrompts = listOf(
            "Search inside yourself for courage",
            "Can you google search this internally?",
            "Look up this concept in your knowledge base",
            "Find the internet definition of recursion"
        )

        for (prompt in searchWordsPrompts) {
            val req = ProviderRequest(userMessage = prompt, requiresWebSearch = false)
            val result = classifier.classify(req)
            assertNotEquals("Prompt '$prompt' must NOT classify as WEB_RESEARCH without structured signal",
                TaskIntent.WEB_RESEARCH, result.primaryIntent)
        }
    }

    @Test
    fun testStructuredSpecializedIntents() {
        // Calculation
        assertEquals(TaskIntent.CALCULATION, classifier.classify(ProviderRequest("calculate (50 + 20) / 2")).primaryIntent)

        // Unit Conversion
        assertEquals(TaskIntent.UNIT_CONVERSION, classifier.classify(ProviderRequest("convert 100 km to miles")).primaryIntent)

        // Date / Time
        assertEquals(TaskIntent.DATE_TIME, classifier.classify(ProviderRequest("what time is it?")).primaryIntent)

        // Weather
        assertEquals(TaskIntent.WEATHER, classifier.classify(ProviderRequest("what is the weather in Paris today?")).primaryIntent)

        // Code
        assertEquals(TaskIntent.CODE, classifier.classify(ProviderRequest("write a function in Kotlin to sort a list")).primaryIntent)
        assertEquals(TaskIntent.CODE, classifier.classify(ProviderRequest("```kotlin\nval x = 10\n```")).primaryIntent)

        // Productivity
        assertEquals(TaskIntent.PRODUCTIVITY, classifier.classify(ProviderRequest("create a todo list for grocery shopping")).primaryIntent)
    }

    @Test
    fun testNoProviderOrNetworkDependency() {
        // Same inputs produce identical pure outputs without any external calls
        val req = ProviderRequest(
            userMessage = "calculate 99 * 99",
            history = listOf(ProviderMessage(ProviderRole.USER, "hi"))
        )

        val res1 = classifier.classify(req)
        val res2 = classifier.classify(req)

        assertEquals(res1.primaryIntent, res2.primaryIntent)
        assertEquals(res1.secondaryIntents, res2.secondaryIntents)
        assertEquals(res1.confidence, res2.confidence, 0.0001f)
        assertEquals(res1.reasoningSignals, res2.reasoningSignals)
    }
}
