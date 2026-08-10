package co.aura.memory

import co.aura.domain.model.MemoryFragment
import co.aura.domain.repository.MemoryRepository

class KeywordMemoryRetriever(
    private val memoryRepository: MemoryRepository
) : MemoryRetriever {

    override suspend fun retrieveRelevantMemories(query: String, limit: Int): List<MemoryFragment> {
        val allMemories = memoryRepository.getMemoryFragments()

        // Extract words of length > 3 as query keywords (lowercase, ignoring basic punctuation)
        val keywords = query.lowercase()
            .split(Regex("[\\s,?.!]+"))
            .map { it.trim() }
            .filter { it.length > 3 }

        // If query is empty or doesn't have keywords, default to pinned items
        val matched = if (keywords.isEmpty()) {
            allMemories.filter { it.pinned }
        } else {
            allMemories.filter { memory ->
                val contentLower = memory.content.lowercase()
                keywords.any { keyword -> contentLower.contains(keyword) }
            }
        }

        // Sort: 1) Pinned first, 2) Importance descending, 3) Created time descending
        val sorted = matched.sortedWith(
            compareByDescending<MemoryFragment> { it.pinned }
                .thenByDescending { it.importance }
                .thenByDescending { it.createdAt }
        ).take(limit)

        // Update last accessed time for the retrieved memories
        val now = System.currentTimeMillis()
        sorted.forEach { memory ->
            memoryRepository.updateLastAccessed(memory.id, now)
        }

        return sorted
    }
}
