package co.aura.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCategory {
    FACT, PREFERENCE, EVENT
}

@Serializable
data class MemoryFragment(
    val id: String,
    val content: String,
    val embedding: List<Float>?,
    val category: MemoryCategory,
    val pinned: Boolean = false,
    val importance: Int = 5,
    val createdAt: Long,
    val lastAccessedAt: Long
)
