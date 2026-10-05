package co.aura.memory

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.MemoryCategory
import co.aura.domain.model.MemoryFragment
import co.aura.domain.repository.ConversationRepository
import co.aura.domain.repository.ConversationSummaryRepository
import co.aura.domain.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

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
    private val memoryRetriever: MemoryRetriever,
    private val conversationSummaryRepository: ConversationSummaryRepository? = null
) : MemoryManager {

    private val synonymMap = mapOf(
        "likes" to "prefer",
        "like" to "prefer",
        "prefers" to "prefer",
        "preferred" to "prefer",
        "wants" to "prefer",
        "want" to "prefer",
        "loves" to "prefer",
        "love" to "prefer",
        "answers" to "response",
        "answer" to "response",
        "responses" to "response",
        "replies" to "response",
        "reply" to "response",
        "short" to "concise",
        "brief" to "concise",
        "coding" to "code",
        "programmer" to "code",
        "programming" to "code"
    )

    private val stopWords = setOf(
        "a", "an", "the", "and", "or", "to", "for", "in", "on", "at", "of", "with", "is", "are",
        "was", "were", "be", "been", "i", "me", "my", "you", "your", "user", "jarvis", "that", "this"
    )

    override suspend fun storeMemory(content: String, category: String, score: Int): Boolean = withContext(Dispatchers.Default) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext false

        // Security Business Rule: Never store API keys, tokens, passwords
        val lower = trimmed.lowercase()
        val sensitiveKeywords = listOf("password", "api_key", "apikey", "secret", "token", "credential", "auth_token")
        if (sensitiveKeywords.any { lower.contains(it) }) {
            return@withContext false
        }

        val mappedCategory = try {
            MemoryCategory.valueOf(category.uppercase())
        } catch (e: Exception) {
            when (category.uppercase()) {
                "PROJECT" -> MemoryCategory.PROJECT
                "PERSON" -> MemoryCategory.PERSON
                "ROUTINE" -> MemoryCategory.ROUTINE
                "CONTEXT" -> MemoryCategory.CONTEXT
                "PREFERENCE" -> MemoryCategory.PREFERENCE
                else -> MemoryCategory.FACT
            }
        }

        val now = System.currentTimeMillis()
        val allMemories = try {
            memoryRepository.getMemoryFragments()
        } catch (e: Exception) {
            AuraLogger.w(LogCategory.AI, "Could not fetch memories for deduplication: ${e.message}")
            emptyList()
        }

        // Check for duplicates or near-equivalent memories
        val candidateTokens = normalizeToSemanticTokens(trimmed)
        val existingMatch = allMemories.firstOrNull { existing ->
            if (existing.content.equals(trimmed, ignoreCase = true)) return@firstOrNull true
            val existingTokens = normalizeToSemanticTokens(existing.content)
            areSemanticallyEquivalent(candidateTokens, existingTokens)
        }

        if (existingMatch != null) {
            AuraLogger.i(LogCategory.AI, "[MEMORY] Equivalent memory found. Updating id=${existingMatch.id} instead of creating duplicate.")
            val updated = existingMatch.copy(
                content = trimmed, // Update to latest user phrasing
                category = mappedCategory,
                importance = maxOf(existingMatch.importance, score),
                lastAccessedAt = now,
                updatedAt = now
            )
            try {
                memoryRepository.saveMemoryFragment(updated)
            } catch (e: Exception) {
                AuraLogger.w(LogCategory.AI, "Failed to update existing memory: ${e.message}")
                return@withContext false
            }
            return@withContext true
        }

        val fragment = MemoryFragment(
            id = "mem_${now}_${trimmed.hashCode().toLong() and 0xFFFFFFFFL}_${(0..9999).random()}",
            content = trimmed,
            embedding = null,
            category = mappedCategory,
            pinned = false,
            importance = score,
            createdAt = now,
            lastAccessedAt = now,
            updatedAt = now
        )

        try {
            memoryRepository.saveMemoryFragment(fragment)
            AuraLogger.i(LogCategory.AI, "[MEMORY] Saved new memory: \"$trimmed\" (Category: $mappedCategory, Importance: $score)")
            true
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.AI, "Failed to save memory fragment: ${e.message}")
            false
        }
    }

    private fun normalizeToSemanticTokens(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9_]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && !stopWords.contains(it) }
            .map { synonymMap[it] ?: it }
            .toSet()
    }

    private fun areSemanticallyEquivalent(tokens1: Set<String>, tokens2: Set<String>): Boolean {
        if (tokens1.isEmpty() || tokens2.isEmpty()) return false
        val intersection = tokens1.intersect(tokens2)
        val union = tokens1.union(tokens2)
        val jaccard = intersection.size.toDouble() / union.size.toDouble()
        // If 55% or more of semantic tokens overlap, or intersection contains at least 3 key words
        return jaccard >= 0.55 || (intersection.size >= 3 && intersection.size >= minOf(tokens1.size, tokens2.size) - 1)
    }

    override suspend fun searchMemories(query: String): List<MemoryFragment> = withContext(Dispatchers.Default) {
        val all = try {
            memoryRepository.getMemoryFragments()
        } catch (e: Exception) {
            emptyList()
        }
        if (query.trim().isEmpty()) return@withContext all
        val lowerQuery = query.lowercase().trim()
        all.filter { it.content.lowercase().contains(lowerQuery) }
    }

    override suspend fun retrieveRelevantMemories(query: String, limit: Int): List<MemoryFragment> = withContext(Dispatchers.Default) {
        try {
            memoryRetriever.retrieveRelevantMemories(query, limit)
        } catch (e: Exception) {
            AuraLogger.w(LogCategory.AI, "Error retrieving memories: ${e.message}")
            emptyList()
        }
    }

    override suspend fun pinMemory(memoryId: String): Boolean = withContext(Dispatchers.Default) {
        try {
            val all = memoryRepository.getMemoryFragments()
            val match = all.firstOrNull { it.id == memoryId } ?: return@withContext false
            val newPinned = !match.pinned
            memoryRepository.updatePinnedStatus(memoryId, newPinned)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun forgetMemory(memoryId: String): Boolean = withContext(Dispatchers.Default) {
        try {
            memoryRepository.deleteMemoryFragment(memoryId)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun summarizeOldConversations(): String = withContext(Dispatchers.Default) {
        try {
            val messages = conversationRepository.getMessages(50).firstOrNull() ?: emptyList()
            if (messages.size <= 6) {
                return@withContext conversationSummaryRepository?.getSummary("session_default") ?: ""
            }

            // Summarize older messages excluding the most recent 4
            val olderMessages = messages.dropLast(4)
            if (olderMessages.isEmpty()) {
                return@withContext conversationSummaryRepository?.getSummary("session_default") ?: ""
            }

            val summaryBuilder = StringBuilder()
            val existingSummary = conversationSummaryRepository?.getSummary("session_default")
            if (!existingSummary.isNullOrBlank()) {
                summaryBuilder.append(existingSummary.trim()).append(" ")
            }

            // Extract concise key points from user turns in older messages
            val userTopics = olderMessages
                .filter { it.sender == co.aura.domain.model.MessageSender.USER }
                .map { it.content.trim() }
                .filter { it.length > 5 }
                .takeLast(5)

            if (userTopics.isNotEmpty()) {
                val newSummaryPoints = "Earlier conversation touched on: " + userTopics.joinToString("; ") {
                    if (it.length > 60) it.take(60) + "..." else it
                }
                summaryBuilder.append(newSummaryPoints)
            }

            val finalSummary = summaryBuilder.toString().trim()
            if (finalSummary.isNotBlank()) {
                conversationSummaryRepository?.saveSummary("session_default", finalSummary, olderMessages.lastOrNull()?.id)
            }
            finalSummary
        } catch (e: Exception) {
            AuraLogger.w(LogCategory.AI, "Summarization failed: ${e.message}")
            conversationSummaryRepository?.getSummary("session_default") ?: ""
        }
    }

    override suspend fun scoreMemoryImportance(content: String): Int {
        val lower = content.lowercase()
        return when {
            lower.contains("favorite") || lower.contains("prefer") || lower.contains("always") -> 8
            lower.contains("never") || lower.contains("dislike") || lower.contains("hate") -> 7
            lower.contains("project") || lower.contains("building") || lower.contains("work on") -> 7
            lower.contains("name is") || lower.contains("call me") -> 9
            else -> 5
        }
    }
}

