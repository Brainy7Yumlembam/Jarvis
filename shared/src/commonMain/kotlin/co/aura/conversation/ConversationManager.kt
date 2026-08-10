package co.aura.conversation

import co.aura.ai.AIProvider
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import co.aura.domain.repository.ConversationRepository
import co.aura.memory.MemoryManager
import co.aura.memory.MemoryRetriever
import co.aura.conversation.ContextBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull

interface ConversationManager {
    suspend fun processUserMessage(text: String): String
    fun getMessages(): Flow<List<ChatMessage>>
    suspend fun clearSession()
}

class ConversationManagerImpl(
    private val aiProvider: AIProvider,
    private val memoryManager: MemoryManager,
    private val personalityEngine: PersonalityEngine,
    private val conversationRepository: ConversationRepository,
    private val contextBuilder: ContextBuilder
) : ConversationManager {

    private val inMemoryHistory = mutableListOf<ChatMessage>()
    private var isHistoryLoaded = false

    private suspend fun ensureHistoryLoaded() {
        if (isHistoryLoaded) return
        try {
            val saved = conversationRepository.getMessages(50).firstOrNull() ?: emptyList()
            inMemoryHistory.clear()
            inMemoryHistory.addAll(saved.reversed())
            isHistoryLoaded = true
        } catch (e: Exception) {
            // Ignore
        }
    }

    override suspend fun processUserMessage(text: String): String {
        ensureHistoryLoaded()

        val cleaned = text.trim().removeSuffix(".").removeSuffix("?").removeSuffix("!")
        val lower = cleaned.lowercase()

        // 1. Remember Command
        val rememberPrefixes = listOf("remember that ", "remember ")
        var isRemember = false
        var rememberContent = ""
        for (prefix in rememberPrefixes) {
            if (lower.startsWith(prefix)) {
                isRemember = true
                rememberContent = cleaned.substring(prefix.length).trim()
                break
            }
        }

        if (isRemember && rememberContent.isNotEmpty()) {
            val score = memoryManager.scoreMemoryImportance(rememberContent)
            val success = memoryManager.storeMemory(rememberContent, "PREFERENCE", score)
            val responseText = if (success) {
                "I will remember: $rememberContent"
            } else {
                "Sorry, I couldn't save that memory. (It may contain sensitive credentials)."
            }
            saveMessageAndHistory(text, responseText)
            return responseText
        }

        // 2. Forget Command
        val forgetPrefixes = listOf("forget that ", "forget ")
        var isForget = false
        var forgetTarget = ""
        for (prefix in forgetPrefixes) {
            if (lower.startsWith(prefix)) {
                isForget = true
                forgetTarget = cleaned.substring(prefix.length).trim()
                break
            }
        }

        if (isForget && forgetTarget.isNotEmpty()) {
            val allMemories = memoryManager.searchMemories("")
            val matches = allMemories.filter { it.content.lowercase().contains(forgetTarget.lowercase()) }

            val responseText = when {
                matches.isEmpty() -> {
                    "I couldn't find any memory matching: \"$forgetTarget\"."
                }
                matches.size == 1 -> {
                    val targetId = matches.first().id
                    memoryManager.forgetMemory(targetId)
                    "I have forgotten: \"${matches.first().content}\"."
                }
                else -> {
                    "I found multiple memories related to \"$forgetTarget\". Which one would you like me to forget?\n" +
                            matches.map { "- ${it.content}" }.joinToString("\n")
                }
            }
            saveMessageAndHistory(text, responseText)
            return responseText
        }

        // 3. Query Command
        val queryPhrases = listOf("what do you remember about me", "show me what you remember about me")
        if (queryPhrases.any { lower.contains(it) }) {
            val allMemories = memoryManager.searchMemories("")
            val responseText = if (allMemories.isEmpty()) {
                "I don't have any saved memories about you yet."
            } else {
                "Here is what I remember about you:\n" +
                        allMemories.map { "- ${it.content}" }.joinToString("\n")
            }
            saveMessageAndHistory(text, responseText)
            return responseText
        }

        // 4. Normal Conversation Message
        val userMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis()}",
            sessionId = "session_default",
            sender = MessageSender.USER,
            content = text,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(userMsg)
        try {
            conversationRepository.saveMessage(userMsg)
        } catch (e: Exception) {
            // Ignore DB cache errors
        }

        val systemInstruction = "You are a personal assistant."
        val personalityPrompt = personalityEngine.getSystemInstructions()
        
        // Retrieve relevant memories for the conversation
        val relevantMemories = memoryManager.retrieveRelevantMemories(text, limit = 5)

        val contextPrompt = contextBuilder.buildContext(
            systemInstructions = systemInstruction,
            personalityPrompt = personalityPrompt,
            recentMessages = inMemoryHistory.takeLast(10),
            relevantMemories = relevantMemories,
            currentRequest = text
        )

        val responseText = aiProvider.generateResponse(contextPrompt)

        val assistantMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis() + 1}",
            sessionId = "session_default",
            sender = MessageSender.ASSISTANT,
            content = responseText,
            timestamp = System.currentTimeMillis() + 1,
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(assistantMsg)
        try {
            conversationRepository.saveMessage(assistantMsg)
        } catch (e: Exception) {
            // Ignore DB cache errors
        }

        return responseText
    }

    private suspend fun saveMessageAndHistory(userText: String, assistantText: String) {
        val userMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis()}",
            sessionId = "session_default",
            sender = MessageSender.USER,
            content = userText,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(userMsg)
        try {
            conversationRepository.saveMessage(userMsg)
        } catch (e: Exception) {}

        val assistantMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis() + 1}",
            sessionId = "session_default",
            sender = MessageSender.ASSISTANT,
            content = assistantText,
            timestamp = System.currentTimeMillis() + 1,
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(assistantMsg)
        try {
            conversationRepository.saveMessage(assistantMsg)
        } catch (e: Exception) {}
    }

    override fun getMessages(): Flow<List<ChatMessage>> {
        return conversationRepository.getMessages(50)
    }

    override suspend fun clearSession() {
        inMemoryHistory.clear()
        try {
            conversationRepository.clearHistory()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
