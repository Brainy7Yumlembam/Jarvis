package co.aura.test

import co.aura.ai.AIProvider
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryFragment
import co.aura.domain.repository.ConversationRepository
import co.aura.domain.repository.MemoryRepository
import co.aura.domain.model.UserProfile
import co.aura.memory.MemoryManager
import co.aura.security.PermissionManager
import co.aura.voice.TextToSpeechEngine
import co.aura.voice.VoiceRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.channels.awaitClose

open class FakeAIProvider : AIProvider {
    var responseToReturn = "Mock response from fake LLM provider"
    var shouldThrowError = false
    var errorToThrow: Exception = Exception("Network request failed")

    open override suspend fun generateResponse(prompt: String): String {
        if (shouldThrowError) throw errorToThrow
        return responseToReturn
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        if (shouldThrowError) throw errorToThrow
        emit(responseToReturn)
    }

    override suspend fun summarize(text: String): String = "Summary: $text"
    override suspend fun extractIntent(text: String): String = "{}"
}

class FakeVoiceRecognizer : VoiceRecognizer {
    private val listeners = mutableListOf<kotlinx.coroutines.channels.ProducerScope<co.aura.voice.RecognitionEvent>>()
    private val transcriptListeners = mutableListOf<kotlinx.coroutines.channels.ProducerScope<String>>()
    var stopListeningCalled = false
    var cancelCalled = false

    override fun startListeningEvents(): Flow<co.aura.voice.RecognitionEvent> = kotlinx.coroutines.flow.callbackFlow {
        trySend(co.aura.voice.RecognitionEvent.ReadyForSpeech)
        synchronized(listeners) {
            listeners.add(this)
        }
        awaitClose {
            synchronized(listeners) {
                listeners.remove(this)
            }
        }
    }

    override fun startListening(): Flow<String> = kotlinx.coroutines.flow.callbackFlow {
        synchronized(transcriptListeners) {
            transcriptListeners.add(this)
        }
        awaitClose {
            synchronized(transcriptListeners) {
                transcriptListeners.remove(this)
            }
        }
    }

    fun emitReadyForSpeech() {
        val list = synchronized(listeners) { listeners.toList() }
        list.forEach { it.trySend(co.aura.voice.RecognitionEvent.ReadyForSpeech) }
    }

    fun emitTranscript(text: String) {
        val list = synchronized(listeners) { listeners.toList() }
        list.forEach { it.trySend(co.aura.voice.RecognitionEvent.PartialResult(text)) }
        val tList = synchronized(transcriptListeners) { transcriptListeners.toList() }
        tList.forEach { it.trySend(text) }
    }

    fun emitFinalResult(text: String) {
        val list = synchronized(listeners) { listeners.toList() }
        list.forEach { it.trySend(co.aura.voice.RecognitionEvent.FinalResult(text)) }
        val tList = synchronized(transcriptListeners) { transcriptListeners.toList() }
        tList.forEach { it.trySend(text) }
    }

    fun completeListening() {
        synchronized(listeners) {
            listeners.toList().forEach { it.close() }
            listeners.clear()
        }
        synchronized(transcriptListeners) {
            transcriptListeners.toList().forEach { it.close() }
            transcriptListeners.clear()
        }
    }

    override fun stopListening() {
        stopListeningCalled = true
    }

    override fun cancel() {
        cancelCalled = true
    }
}

class FakeInterruptionMonitor : co.aura.voice.InterruptionMonitor {
    var isMonitoringCalled = false
    override var isMonitoring: Boolean = false
    override var isSupportedOnPlatform: Boolean = true
    var onInterruptionCallback: ((String) -> Unit)? = null

    override fun startMonitoring(currentSpeakingText: String, onInterruption: (phrase: String) -> Unit) {
        isMonitoringCalled = true
        isMonitoring = true
        onInterruptionCallback = onInterruption
    }

    override fun stopMonitoring() {
        isMonitoring = false
        onInterruptionCallback = null
    }

    fun simulateInterruption(phrase: String = "stop") {
        onInterruptionCallback?.invoke(phrase)
    }
}


class FakeTextToSpeechEngine : TextToSpeechEngine {
    var textSpoken: String? = null
    var stopCalled = false
    var speakingStatus = false

    override suspend fun speak(text: String): Boolean {
        textSpoken = text
        speakingStatus = true
        return true
    }

    override fun stop() {
        stopCalled = true
        speakingStatus = false
    }

    override fun isSpeaking(): Boolean = speakingStatus
}

class FakePermissionManager : PermissionManager {
    var permissionsMap = mutableMapOf<String, Boolean>()

    override fun hasPermission(permission: String): Boolean {
        return permissionsMap[permission] ?: true
    }

    override fun requestPermission(permission: String): Flow<Boolean> = flow {
        emit(hasPermission(permission))
    }
}

class FakeConversationRepository : ConversationRepository {
    val messages = mutableListOf<ChatMessage>()
    override fun getMessages(limit: Int): Flow<List<ChatMessage>> = flow {
        emit(messages.sortedByDescending { it.timestamp }.take(limit))
    }
    override suspend fun saveMessage(message: ChatMessage) { messages.add(message) }
    override suspend fun clearHistory() { messages.clear() }
}

class FakeConversationSummaryRepository : co.aura.domain.repository.ConversationSummaryRepository {
    var savedSummary: String? = null
    var lastMessageId: String? = null

    override suspend fun getSummary(sessionId: String): String? = savedSummary
    override suspend fun saveSummary(sessionId: String, summary: String, lastMessageId: String?) {
        this.savedSummary = summary
        this.lastMessageId = lastMessageId
    }
    override suspend fun clearSummary(sessionId: String) {
        savedSummary = null
        lastMessageId = null
    }
}

class FakeMemoryExtractor(private val memoryManager: MemoryManager) : co.aura.memory.MemoryExtractor {
    override suspend fun extractAndSaveAsync(userMessage: String, assistantResponse: String): Boolean {
        if (userMessage.startsWith("I prefer ") || userMessage.startsWith("My name is ") || userMessage.contains("building JARVIS")) {
            return memoryManager.storeMemory(userMessage, "PREFERENCE", 5)
        }
        return false
    }
}

class FakeMemoryManager : MemoryManager {
    val memories = mutableListOf<MemoryFragment>()

    override suspend fun storeMemory(content: String, category: String, score: Int): Boolean {
        if (content.contains("secret") || content.contains("password")) return false
        val cat = try {
            co.aura.domain.model.MemoryCategory.valueOf(category.uppercase())
        } catch (e: Exception) {
            co.aura.domain.model.MemoryCategory.FACT
        }
        memories.add(
            MemoryFragment(
                id = "mem_${System.currentTimeMillis()}_${content.hashCode()}",
                content = content,
                embedding = null,
                category = cat,
                pinned = false,
                importance = score,
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    override suspend fun searchMemories(query: String): List<MemoryFragment> {
        if (query.isEmpty()) return memories
        return memories.filter { it.content.contains(query) }
    }

    override suspend fun retrieveRelevantMemories(query: String, limit: Int): List<MemoryFragment> {
        return memories.take(limit)
    }

    override suspend fun pinMemory(memoryId: String): Boolean {
        val index = memories.indexOfFirst { it.id == memoryId }
        if (index != -1) {
            val old = memories[index]
            memories[index] = old.copy(pinned = !old.pinned)
            return true
        }
        return false
    }

    override suspend fun forgetMemory(memoryId: String): Boolean {
        return memories.removeAll { it.id == memoryId }
    }

    override suspend fun summarizeOldConversations(): String = ""
    override suspend fun scoreMemoryImportance(content: String): Int = 5
}

class FakeMemoryRepository : MemoryRepository {
    val memories = mutableListOf<MemoryFragment>()
    var semanticProfile = co.aura.domain.model.UserProfile(id = "user_default", name = "Aura User", preferences = emptyMap())

    override suspend fun queryVectorMemory(queryEmbedding: List<Float>, limit: Int): List<MemoryFragment> = emptyList()

    override suspend fun getMemoryFragments(): List<MemoryFragment> = memories

    override suspend fun saveMemoryFragment(fragment: MemoryFragment) {
        memories.removeAll { it.id == fragment.id }
        memories.add(fragment)
    }

    override suspend fun deleteMemoryFragment(id: String) {
        memories.removeAll { it.id == id }
    }

    override suspend fun updatePinnedStatus(id: String, pinned: Boolean) {
        val index = memories.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = memories[index]
            memories[index] = old.copy(pinned = pinned)
        }
    }

    override suspend fun updateLastAccessed(id: String, timestamp: Long) {
        val index = memories.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = memories[index]
            memories[index] = old.copy(lastAccessedAt = timestamp)
        }
    }

    override suspend fun getSemanticProfile(): co.aura.domain.model.UserProfile = semanticProfile

    override suspend fun updateSemanticProfile(profile: co.aura.domain.model.UserProfile) {
        semanticProfile = profile
    }
}

class FakeSecurityManager : co.aura.security.SecurityManager {
    private val storage = mutableMapOf<String, String>()

    override suspend fun authorizeAction(action: co.aura.actions.Action): Boolean = true
    override suspend fun confirmSensitiveAction(action: co.aura.actions.Action, promptMessage: String): Boolean = true

    override suspend fun saveSecureToken(key: String, token: String) {
        if (token.isEmpty()) storage.remove(key) else storage[key] = token
    }

    override suspend fun getSecureToken(key: String): String? = storage[key]
}
