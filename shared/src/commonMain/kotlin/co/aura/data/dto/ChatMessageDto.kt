package co.aura.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(
    @SerialName("id") val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sender") val sender: String,
    @SerialName("content") val content: String,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("user_id") val userId: String
)
