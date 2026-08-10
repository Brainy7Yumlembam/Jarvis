package co.aura.domain.repository

import co.aura.domain.model.MemoryFragment
import co.aura.domain.model.UserProfile

interface MemoryRepository {
    suspend fun queryVectorMemory(queryEmbedding: List<Float>, limit: Int): List<MemoryFragment>
    suspend fun getMemoryFragments(): List<MemoryFragment>
    suspend fun saveMemoryFragment(fragment: MemoryFragment)
    suspend fun deleteMemoryFragment(id: String)
    suspend fun updatePinnedStatus(id: String, pinned: Boolean)
    suspend fun updateLastAccessed(id: String, timestamp: Long)
    suspend fun getSemanticProfile(): UserProfile
    suspend fun updateSemanticProfile(profile: UserProfile)
}
