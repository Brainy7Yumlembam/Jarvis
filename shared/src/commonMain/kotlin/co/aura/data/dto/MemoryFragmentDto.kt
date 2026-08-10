package co.aura.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MemoryFragmentDto(
    @SerialName("id") val id: String,
    @SerialName("content") val content: String,
    @SerialName("embedding") val embedding: List<Float>?,
    @SerialName("category") val category: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_accessed_at") val lastAccessedAt: String,
    @SerialName("user_id") val userId: String
)
