package co.aura.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageSender {
    USER, ASSISTANT, SYSTEM
}

@Serializable
enum class MessageStatus {
    PENDING, SENT, FAILED
}

@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val sender: MessageSender,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus
)
