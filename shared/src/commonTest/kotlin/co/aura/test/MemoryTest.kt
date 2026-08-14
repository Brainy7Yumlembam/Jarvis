package co.aura.test

import co.aura.conversation.*
import co.aura.domain.model.*
import co.aura.memory.*
import co.aura.presentation.viewmodel.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemoryTest {

    @Test
    fun testMemoryPersistenceAndRetrieval() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)

        // Verify start empty
        assertTrue(repo.getMemoryFragments().isEmpty())

        // Save memory
        val success = manager.storeMemory("I prefer Kotlin", "PREFERENCE", 5)
        assertTrue(success)
        assertEquals(1, repo.getMemoryFragments().size)

        // Retrieve memories
        val retrieved = manager.searchMemories("")
        assertEquals(1, retrieved.size)
        assertEquals("I prefer Kotlin", retrieved.first().content)
    }

    @Test
    fun testMemoryDeletionAndPinUnpin() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)

        manager.storeMemory("Kotlin is good", "PREFERENCE", 5)
        val memory = manager.searchMemories("").first()

        // Test Pinning
        assertFalse(memory.pinned)
        val pinSuccess = manager.pinMemory(memory.id)
        assertTrue(pinSuccess)
        assertTrue(manager.searchMemories("").first().pinned)

        // Test Unpinning
        val unpinSuccess = manager.pinMemory(memory.id)
        assertTrue(unpinSuccess)
        assertFalse(manager.searchMemories("").first().pinned)

        // Test Deletion
        val deleteSuccess = manager.forgetMemory(memory.id)
        assertTrue(deleteSuccess)
        assertTrue(repo.getMemoryFragments().isEmpty())
    }

    @Test
    fun testKeywordRelevanceAndImportanceAndDuplicateMemories() = runTest {
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val manager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)

        // Save multiple memories with different importances
        manager.storeMemory("I like to code in Kotlin", "PREFERENCE", 5)
        manager.storeMemory("Kotlin makes coding fast", "PREFERENCE", 8)
        manager.storeMemory("Python is also used", "FACT", 4)

        // Retrieve relevant memories for query "Kotlin"
        val retrieved = manager.retrieveRelevantMemories("Code in Kotlin language", limit = 5)
        // Should only match Kotlin related memories (2 matches)
        assertEquals(2, retrieved.size)

        // Pinned/Importance Sorting: "Kotlin makes coding fast" has importance 8, should be first
        assertEquals("Kotlin makes coding fast", retrieved[0].content)
        assertEquals("I like to code in Kotlin", retrieved[1].content)

        // Test duplicate insertion of same ID is handled gracefully (replaces/updates)
        val firstMem = retrieved[0]
        val duplicateMem = firstMem.copy(content = "Kotlin makes coding extremely fast")
        repo.saveMemoryFragment(duplicateMem)
        assertEquals(3, repo.getMemoryFragments().size) // still total 3
        val updated = repo.getMemoryFragments().first { it.id == firstMem.id }
        assertEquals("Kotlin makes coding extremely fast", updated.content)
    }

    @Test
    fun testExplicitRememberCommand() = runTest {
        val aiProvider = FakeAIProvider()
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val memoryManager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)
        val personalityEngine = PersonalityEngineImpl()
        val conversationRepo = FakeConversationRepository()
        val contextBuilder = ContextBuilderImpl()

        val conversationManager = ConversationManagerImpl(
            aiProvider, memoryManager, personalityEngine, conversationRepo, contextBuilder
        )

        // Send explicit remember request
        val response = conversationManager.processUserMessage("Remember that I prefer tabs over spaces")
        assertEquals("I will remember: I prefer tabs over spaces", response)

        // Check it was saved to memory store
        val memories = memoryManager.searchMemories("")
        assertEquals(1, memories.size)
        assertEquals("I prefer tabs over spaces", memories.first().content)
    }

    @Test
    fun testExplicitForgetCommandAndSafety() = runTest {
        val aiProvider = FakeAIProvider()
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val memoryManager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)
        val personalityEngine = PersonalityEngineImpl()
        val conversationRepo = FakeConversationRepository()
        val contextBuilder = ContextBuilderImpl()

        val conversationManager = ConversationManagerImpl(
            aiProvider, memoryManager, personalityEngine, conversationRepo, contextBuilder
        )

        // 1. Delete when no memory exists
        val responseNone = conversationManager.processUserMessage("Forget that I like Kotlin")
        assertEquals("I couldn't find any memory matching: \"I like Kotlin\".", responseNone)

        // Save a memory
        memoryManager.storeMemory("I like Kotlin", "PREFERENCE", 5)

        // 2. Exactly one match exists -> delete it
        val responseOne = conversationManager.processUserMessage("Forget that I like Kotlin")
        assertEquals("I have forgotten: \"I like Kotlin\".", responseOne)
        assertTrue(memoryManager.searchMemories("").isEmpty())

        // Save multiple matches for ambiguous test
        memoryManager.storeMemory("I prefer Kotlin for Android development", "PREFERENCE", 5)
        memoryManager.storeMemory("Kotlin is my primary programming language", "PREFERENCE", 8)

        // 3. Multiple plausible matches exist -> do NOT delete, return clarification
        val responseAmbiguous = conversationManager.processUserMessage("Forget Kotlin")
        assertTrue(responseAmbiguous.contains("Which one would you like me to forget?"))
        // Check neither was deleted
        assertEquals(2, memoryManager.searchMemories("").size)
    }

    @Test
    fun testWhatDoYouRememberCommand() = runTest {
        val aiProvider = FakeAIProvider()
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val memoryManager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)
        val personalityEngine = PersonalityEngineImpl()
        val conversationRepo = FakeConversationRepository()
        val contextBuilder = ContextBuilderImpl()

        val conversationManager = ConversationManagerImpl(
            aiProvider, memoryManager, personalityEngine, conversationRepo, contextBuilder
        )

        val responseEmpty = conversationManager.processUserMessage("What do you remember about me?")
        assertEquals("I don't have any saved memories about you yet.", responseEmpty)

        memoryManager.storeMemory("I prefer Kotlin", "PREFERENCE", 5)
        memoryManager.storeMemory("I live in San Francisco", "FACT", 5)

        val responseWithData = conversationManager.processUserMessage("Show me what you remember about me")
        assertTrue(responseWithData.contains("I prefer Kotlin"))
        assertTrue(responseWithData.contains("I live in San Francisco"))
    }

    @Test
    fun testContextBuilderAndPersonalityEngine() {
        val contextBuilder = ContextBuilderImpl()
        val personalityEngine = PersonalityEngineImpl()

        val systemInst = "You are a cognitive OS."
        val personalityPrompt = personalityEngine.getSystemInstructions()
        val recentMsgs = listOf(
            ChatMessage("1", "session_default", MessageSender.USER, "Hello", 100, MessageStatus.SENT),
            ChatMessage("2", "session_default", MessageSender.ASSISTANT, "Hi, I am Aura.", 101, MessageStatus.SENT)
        )
        val relevantMemories = listOf(
            MemoryFragment("mem1", "User prefers Kotlin", null, MemoryCategory.PREFERENCE, false, 5, 200, 201)
        )

        val context = contextBuilder.buildContext(
            systemInstructions = systemInst,
            personalityPrompt = personalityPrompt,
            recentMessages = recentMsgs,
            relevantMemories = relevantMemories,
            currentRequest = "Explain why I like Kotlin"
        )

        assertTrue(context.contains("You are a cognitive OS."))
        assertTrue(context.contains("Aura"))
        assertTrue(context.contains("User prefers Kotlin"))
        assertTrue(context.contains("Hello"))
        assertTrue(context.contains("Explain why I like Kotlin"))
    }

    @Test
    fun testViewModelStateTransitionsAndHistoryRestoration() = runTest {
        val voiceRecognizer = FakeVoiceRecognizer()
        val ttsEngine = FakeTextToSpeechEngine()
        val aiProvider = FakeAIProvider()
        val repo = FakeMemoryRepository()
        val retriever = KeywordMemoryRetriever(repo)
        val memoryManager = MemoryManagerImpl(repo, FakeConversationRepository(), retriever)
        val personalityEngine = PersonalityEngineImpl()
        val conversationRepo = FakeConversationRepository()
        val contextBuilder = ContextBuilderImpl()

        // Populate database with prior messages to simulate application restart persistence
        val initialMsg = ChatMessage(
            id = "msg_old",
            sessionId = "session_default",
            sender = MessageSender.USER,
            content = "Old hello",
            timestamp = 50,
            status = MessageStatus.SENT
        )
        conversationRepo.saveMessage(initialMsg)

        val conversationManager = ConversationManagerImpl(
            aiProvider, memoryManager, personalityEngine, conversationRepo, contextBuilder
        )

        val viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = FakePermissionManager(),
            securityManager = FakeSecurityManager()
        )

        // Retrieve messages flow and verify restoration
        val restoredMessages = viewModel.messages.first()
        assertEquals(1, restoredMessages.size)
        assertEquals("Old hello", restoredMessages.first().content)

        // Verify VM idle state
        assertEquals(VoiceAssistantState.Idle, viewModel.uiState.value)
    }
}
