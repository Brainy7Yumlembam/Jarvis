package co.aura.conversation

import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryFragment

interface ContextBuilder {
    fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        recentMessages: List<ChatMessage>,
        relevantMemories: List<MemoryFragment>,
        currentRequest: String
    ): String
}

class ContextBuilderImpl : ContextBuilder {
    override fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        recentMessages: List<ChatMessage>,
        relevantMemories: List<MemoryFragment>,
        currentRequest: String
    ): String {
        return buildString {
            append("System Instructions:\n")
            append(systemInstructions)
            append("\n\nPersonality Profile:\n")
            append(personalityPrompt)
            
            if (relevantMemories.isNotEmpty()) {
                append("\n\nStored Memories about the User:\n")
                relevantMemories.forEach { memory ->
                    val pinIndicator = if (memory.pinned) " [Pinned]" else ""
                    append("- ${memory.content} (Category: ${memory.category})$pinIndicator\n")
                }
            }

            if (recentMessages.isNotEmpty()) {
                append("\nRecent Conversation History:\n")
                recentMessages.forEach { msg ->
                    val senderName = when (msg.sender) {
                        co.aura.domain.model.MessageSender.USER -> "User"
                        co.aura.domain.model.MessageSender.ASSISTANT -> "Assistant"
                        co.aura.domain.model.MessageSender.SYSTEM -> "System"
                    }
                    append("$senderName: ${msg.content}\n")
                }
            }

            append("\nCurrent User Request:\n")
            append("User: $currentRequest\n")
            append("Assistant:")
        }
    }
}
