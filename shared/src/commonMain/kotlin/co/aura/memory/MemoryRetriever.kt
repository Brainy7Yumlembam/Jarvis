package co.aura.memory

import co.aura.domain.model.MemoryFragment

interface MemoryRetriever {
    suspend fun retrieveRelevantMemories(query: String, limit: Int = 5): List<MemoryFragment>
}
