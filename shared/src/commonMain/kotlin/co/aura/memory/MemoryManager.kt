package co.aura.memory

import co.aura.domain.model.MemoryFragment
import co.aura.domain.repository.ConversationRepository
import co.aura.domain.repository.MemoryRepository

interface MemoryManager {
    suspend fun storeMemory(content: String, category: String, score: Int): Boolean
    suspend fun searchMemories(query: String): List<MemoryFragment>
    suspend fun retrieveRelevantMemories(query: String, limit: Int = 5): List<MemoryFragment>
    suspend fun pinMemory(memoryId: String): Boolean
    suspend fun forgetMemory(memoryId: String): Boolean
    suspend fun summarizeOldConversations(): String
    suspend fun scoreMemoryImportance(content: String): Int
}

class MemoryManagerImpl(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val memoryRetriever: MemoryRetriever
) : MemoryManager {
    
    override suspend fun storeMemory(content: String, category: String, score: Int): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false

        // Security Business Rule: Never store API keys, tokens, passwords
        val lower = trimmed.lowercase()
        val sensitiveKeywords = listOf("password", "api_key", "apikey", "secret", "token", "credential", "auth_token")
        if (sensitiveKeywords.any { lower.contains(it) }) {
            return false
        }

        val mappedCategory = try {
            co.aura.domain.model.MemoryCategory.valueOf(category.uppercase())
        } catch (e: Exception) {
            co.aura.domain.model.MemoryCategory.FACT
        }

        val fragment = co.aura.domain.model.MemoryFragment(
            id = "mem_${System.currentTimeMillis()}_${trimmed.hashCode().coerceAtLeast(0)}",
            content = trimmed,
            embedding = null,
            category = mappedCategory,
            pinned = false,
            importance = score,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )

        memoryRepository.saveMemoryFragment(fragment)
        return true
    }

    override suspend fun searchMemories(query: String): List<MemoryFragment> {
        val all = memoryRepository.getMemoryFragments()
        if (query.trim().isEmpty()) return all
        val lowerQuery = query.lowercase().trim()
        return all.filter { it.content.lowercase().contains(lowerQuery) }
    }

    override suspend fun retrieveRelevantMemories(query: String, limit: Int): List<MemoryFragment> {
        return memoryRetriever.retrieveRelevantMemories(query, limit)
    }

    override suspend fun pinMemory(memoryId: String): Boolean {
        val all = memoryRepository.getMemoryFragments()
        val match = all.firstOrNull { it.id == memoryId } ?: return false
        val newPinned = !match.pinned
        memoryRepository.updatePinnedStatus(memoryId, newPinned)
        return true
    }

    override suspend fun forgetMemory(memoryId: String): Boolean {
        memoryRepository.deleteMemoryFragment(memoryId)
        return true
    }

    override suspend fun summarizeOldConversations(): String {
        return "Conversation Summary Placeholder"
    }

    override suspend fun scoreMemoryImportance(content: String): Int {
        val lower = content.lowercase()
        return when {
            lower.contains("favorite") || lower.contains("prefer") || lower.contains("always") -> 8
            lower.contains("never") || lower.contains("dislike") || lower.contains("hate") -> 7
            else -> 5
        }
    }
}
