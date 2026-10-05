package co.aura.test

import co.aura.actions.Action
import co.aura.actions.ActionExecutor
import co.aura.actions.ActionParserImpl
import co.aura.actions.ActionRouterImpl
import co.aura.actions.ActionResult
import co.aura.actions.ActionValidatorImpl
import co.aura.actions.AppAliasResolver
import co.aura.actions.CommandBusImpl
import co.aura.actions.OpenAppAction
import co.aura.conversation.*
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryCategory
import co.aura.domain.model.MemoryFragment
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import co.aura.memory.*
import co.aura.voice.SpeechCommandNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class MemoryOptimizationTest {

    @Test
    fun testMemoryDeduplication() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)

        // Store first preference
        val firstSaved = manager.storeMemory("User likes concise responses", "PREFERENCE", 5)
        assertTrue(firstSaved)
        assertEquals(1, repo.getMemoryFragments().size)

        // Store equivalent / synonymous preference
        val secondSaved = manager.storeMemory("User prefers concise answers", "PREFERENCE", 8)
        assertTrue(secondSaved)

        // MUST NOT create duplicate memory entry
        val allMemories = repo.getMemoryFragments()
        assertEquals(1, allMemories.size, "Equivalent memory should be updated instead of duplicated")
        val updated = allMemories.first()
        assertEquals("User prefers concise answers", updated.content)
        assertEquals(8, updated.importance, "Importance should be updated to higher score")
    }

    @Test
    fun testRelevantMemoryRanking() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)

        manager.storeMemory("User prefers dark theme", "PREFERENCE", 5)
        manager.storeMemory("JARVIS uses Compose Multiplatform and Kotlin", "PROJECT", 8)
        manager.storeMemory("User is a software engineer in San Francisco", "PERSON", 6)
        manager.storeMemory("Orange holographic AI core interface", "PROJECT", 9)
        manager.storeMemory("User likes pasta for dinner", "PREFERENCE", 4)

        // Query relevant to the UI / Compose project
        val retrieved = manager.retrieveRelevantMemories("Continue working on the Compose Multiplatform UI", limit = 5)

        assertTrue(retrieved.isNotEmpty())
        // Both the Compose project and Orange holographic UI memories should rank higher than pasta or dark theme
        val topContent = retrieved.map { it.content }
        assertTrue(topContent.any { it.contains("Compose Multiplatform") }, "Should retrieve Compose Multiplatform memory")
        assertFalse(topContent.contains("User likes pasta for dinner"), "Unrelated pasta memory should not be in top results")
    }

    @Test
    fun testMemoryLimit3To5() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)

        // Insert 10 distinct memories on the same topic
        val distinctMemories = listOf(
            "JARVIS uses Compose Multiplatform for UI",
            "JARVIS backend connects to Supabase database",
            "JARVIS voice recognizes speech via AndroidSpeechRecognizer",
            "JARVIS text to speech uses AndroidTTSEngine",
            "JARVIS holographic core animates with Canvas shader",
            "JARVIS local memory stores fragments in SQLDelight",
            "JARVIS actions launch external Android applications",
            "JARVIS contact resolver queries phone contacts",
            "JARVIS interruption detector listens for stop keywords",
            "JARVIS sentence chunker splits streaming AI responses"
        )
        for (item in distinctMemories) {
            manager.storeMemory(item, "PROJECT", 5)
        }
        assertEquals(10, repo.getMemoryFragments().size)

        // Retrieval with default limit (capped at 5)
        val defaultRetrieved = manager.retrieveRelevantMemories("JARVIS project performance", limit = 10)
        assertTrue(defaultRetrieved.size in 1..5, "Retriever must never return more than 5 memories (got ${defaultRetrieved.size})")

        // ContextBuilder prompt must contain at most 5 memories
        val contextBuilder = ContextBuilderImpl()
        val prompt = contextBuilder.buildContext(
            systemInstructions = "System Instructions",
            personalityPrompt = "Personality",
            recentMessages = emptyList(),
            relevantMemories = defaultRetrieved,
            currentRequest = "Status report"
        )
        val memoryCountInPrompt = prompt.lines().count { it.startsWith("- JARVIS") }
        assertTrue(memoryCountInPrompt <= 5, "Prompt must contain at most 5 memories, had $memoryCountInPrompt")
    }

    @Test
    fun testRecentTurnLimit3To6() {
        val builder = ContextBuilderImpl()
        val messages = (1..20).map { i ->
            ChatMessage(
                id = "msg_$i",
                sessionId = "session_default",
                sender = if (i % 2 == 1) MessageSender.USER else MessageSender.ASSISTANT,
                content = "Turn $i message content",
                timestamp = 1000L + i,
                status = MessageStatus.SENT
            )
        }

        // Pass 20 messages to ContextBuilder
        val context = builder.buildContext(
            systemInstructions = "Base Instructions",
            personalityPrompt = "Personality",
            conversationMode = ConversationMode.HYBRID,
            relevantMemories = emptyList(),
            recentMessages = messages,
            recentActionContext = null,
            conversationSummary = "Prior turns 1-14 summary",
            currentRequest = "Current query"
        )

        // Must NOT contain older turns (e.g. Turn 1 to Turn 14)
        assertFalse(context.contains("Turn 1 message content"), "Should not contain turn 1")
        assertFalse(context.contains("Turn 14 message content"), "Should not contain turn 14")

        // Must contain the last 6 turns (turns 15 to 20)
        assertTrue(context.contains("Turn 15 message content"))
        assertTrue(context.contains("Turn 20 message content"))

        // Count how many turn messages appear in the recent history block
        val turnMatches = (1..20).count { context.contains("Turn $it message content") }
        assertEquals(6, turnMatches, "ContextBuilder must strictly cap recent turns to at most 6 turns")
    }

    @Test
    fun testConversationSummarization() = runTest {
        val convRepo = FakeConversationRepository()
        val summaryRepo = FakeConversationSummaryRepository()
        val memoryRepo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(memoryRepo)
        val manager = MemoryManagerImpl(memoryRepo, convRepo, retriever, summaryRepo)

        // Populate with > 6 messages
        for (i in 1..10) {
            convRepo.saveMessage(
                ChatMessage(
                    id = "msg_$i",
                    sessionId = "session_default",
                    sender = if (i % 2 == 1) MessageSender.USER else MessageSender.ASSISTANT,
                    content = if (i % 2 == 1) "User discussed project topic $i" else "Assistant reply $i",
                    timestamp = 1000L + i,
                    status = MessageStatus.SENT
                )
            )
        }

        val summary = manager.summarizeOldConversations()
        assertTrue(summary.isNotBlank(), "Summary should not be blank for conversation > 6 messages")
        assertEquals(summary, summaryRepo.getSummary("session_default"), "Summary must be persisted in repository")

        // Verify ContextBuilder injects the summary into prompt
        val contextBuilder = ContextBuilderImpl()
        val prompt = contextBuilder.buildContext(
            systemInstructions = "Instructions",
            personalityPrompt = "Personality",
            conversationMode = ConversationMode.HYBRID,
            relevantMemories = emptyList(),
            recentMessages = convRepo.messages.takeLast(4),
            recentActionContext = null,
            conversationSummary = summary,
            currentRequest = "Continue"
        )

        assertTrue(prompt.contains("Conversation Summary (Prior Context):"))
        assertTrue(prompt.contains(summary.trim()))
    }

    @Test
    fun testEphemeralMessagesNotBecomingMemories() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)
        val extractor = MemoryExtractorImpl(manager)

        // Ephemeral messages must NOT be saved
        val ephemeralSamples = listOf(
            "What time is it?",
            "What is the weather today?",
            "Tell me a joke.",
            "Open YouTube",
            "Turn on the flashlight",
            "How are you?",
            "Hello JARVIS",
            "Stop speaking"
        )

        for (sample in ephemeralSamples) {
            val saved = extractor.extractAndSaveAsync(sample, "Here is your answer, sir.")
            assertFalse(saved, "Ephemeral message '$sample' should not be extracted as memory")
        }
        assertTrue(repo.getMemoryFragments().isEmpty(), "No memories should have been stored from ephemeral inputs")

        // Durable messages MUST be saved
        val durable1 = "I prefer concise responses"
        val savedDurable1 = extractor.extractAndSaveAsync(durable1, "Understood, sir.")
        assertTrue(savedDurable1, "Durable preference should be extracted")

        val durable2 = "I am building JARVIS using Compose Multiplatform"
        val savedDurable2 = extractor.extractAndSaveAsync(durable2, "Understood, sir.")
        assertTrue(savedDurable2, "Durable project fact should be extracted")

        assertEquals(2, repo.getMemoryFragments().size, "Only durable memories should be stored")
    }

    @Test
    fun testContextTokenEstimation() {
        val builder = ContextBuilderImpl()
        val memory = MemoryFragment(
            id = "mem1",
            content = "User prefers concise answers",
            embedding = null,
            category = MemoryCategory.PREFERENCE,
            pinned = false,
            importance = 8,
            createdAt = 1000L,
            lastAccessedAt = 1000L
        )

        val prompt = builder.buildContext(
            systemInstructions = "System Instructions",
            personalityPrompt = "Personality Profile",
            conversationMode = ConversationMode.HYBRID,
            relevantMemories = listOf(memory),
            recentMessages = listOf(
                ChatMessage("1", "s1", MessageSender.USER, "Hello", 100, MessageStatus.SENT),
                ChatMessage("2", "s1", MessageSender.ASSISTANT, "Hello sir", 101, MessageStatus.SENT)
            ),
            recentActionContext = "Last opened app: YouTube",
            conversationSummary = "Prior summary",
            currentRequest = "Tell me about my preferences"
        )

        val charCount = prompt.length
        val estimatedTokens = (charCount + 3) / 4
        assertTrue(charCount > 100, "Character count should be positive and realistic")
        assertTrue(estimatedTokens > 20, "Estimated token count should be calculated correctly")
    }

    @Test
    fun testCoreActionsRemainFunctionalWithOptimizedContext() = runTest {
        val commandBus = CommandBusImpl()
        var lastAction: Action? = null
        val executor = object : ActionExecutor {
            override suspend fun executeAction(action: Action): ActionResult {
                lastAction = action
                return ActionResult.Success("Opened ${action.actionType}")
            }
        }
        val actionRouter = ActionRouterImpl(commandBus, executor)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val aiProvider = object : co.aura.ai.AIProvider {
            override suspend fun generateResponse(prompt: String): String = """{
                "type": "ACTION",
                "response": "Opening YouTube, sir.",
                "actions": [
                    {
                        "action": "OPEN_APP",
                        "parameters": { "appName": "YouTube" }
                    }
                ]
            }"""
            override fun streamResponse(prompt: String): Flow<String> = flow { emit(generateResponse(prompt)) }
            override suspend fun summarize(text: String): String = "Summary"
            override suspend fun extractIntent(text: String): String = "{}"
        }

        val memoryRepo = FakeMemoryRepository()
        val convRepo = FakeConversationRepository()
        val summaryRepo = FakeConversationSummaryRepository()
        val retriever = KeywordMemoryRetriever(memoryRepo)
        val memoryManager = MemoryManagerImpl(memoryRepo, convRepo, retriever, summaryRepo)
        val extractor = MemoryExtractorImpl(memoryManager)

        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = convRepo,
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(json),
            actionValidator = ActionValidatorImpl(),
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver(),
            conversationSummaryRepository = summaryRepo,
            memoryExtractor = extractor
        )

        val response = conversationManager.processUserMessage("Open YouTube")
        assertEquals("Opened OPEN_APP", response)
        assertNotNull(lastAction)
        assertIs<OpenAppAction>(lastAction)
        assertEquals("YouTube", (lastAction as OpenAppAction).appName)
    }
}
