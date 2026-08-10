package co.aura.data.repository

import co.aura.core.database.DatabaseHelper
import co.aura.domain.model.MemoryFragment
import co.aura.domain.model.UserProfile
import co.aura.domain.repository.MemoryRepository

class MemoryRepositoryImpl(
    private val databaseHelper: DatabaseHelper
) : MemoryRepository {
    
    override suspend fun queryVectorMemory(queryEmbedding: List<Float>, limit: Int): List<MemoryFragment> {
        return emptyList()
    }

    override suspend fun getMemoryFragments(): List<MemoryFragment> {
        return databaseHelper.localMemoryQueries.getMemoryFragments().executeAsList().map { dbMemory ->
            co.aura.domain.model.MemoryFragment(
                id = dbMemory.id,
                content = dbMemory.content,
                embedding = null,
                category = try {
                    co.aura.domain.model.MemoryCategory.valueOf(dbMemory.category)
                } catch (e: Exception) {
                    co.aura.domain.model.MemoryCategory.FACT
                },
                pinned = dbMemory.pinned == 1L,
                importance = dbMemory.importance.toInt(),
                createdAt = dbMemory.created_at,
                lastAccessedAt = dbMemory.last_accessed_at
            )
        }
    }

    override suspend fun saveMemoryFragment(fragment: MemoryFragment) {
        databaseHelper.localMemoryQueries.insertMemoryFragment(
            id = fragment.id,
            content = fragment.content,
            embedding = null,
            category = fragment.category.name,
            pinned = if (fragment.pinned) 1L else 0L,
            importance = fragment.importance.toLong(),
            created_at = fragment.createdAt,
            last_accessed_at = fragment.lastAccessedAt
        )
    }

    override suspend fun deleteMemoryFragment(id: String) {
        databaseHelper.localMemoryQueries.deleteMemoryFragment(id)
    }

    override suspend fun updatePinnedStatus(id: String, pinned: Boolean) {
        databaseHelper.localMemoryQueries.updatePinnedStatus(
            pinned = if (pinned) 1L else 0L,
            id = id
        )
    }

    override suspend fun updateLastAccessed(id: String, timestamp: Long) {
        databaseHelper.localMemoryQueries.updateLastAccessed(
            last_accessed_at = timestamp,
            id = id
        )
    }

    override suspend fun getSemanticProfile(): UserProfile {
        return UserProfile(id = "user_default", name = "Aura User", preferences = emptyMap())
    }

    override suspend fun updateSemanticProfile(profile: UserProfile) {
    }
}
