package co.aura.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncTask(
    val id: String,
    val operationType: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int
)
