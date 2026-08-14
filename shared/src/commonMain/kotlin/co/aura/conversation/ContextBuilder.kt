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

    fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        conversationMode: ConversationMode,
        relevantMemories: List<MemoryFragment>,
        recentMessages: List<ChatMessage>,
        recentActionContext: String?,
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
        return buildContext(
            systemInstructions = systemInstructions,
            personalityPrompt = personalityPrompt,
            conversationMode = ConversationMode.HYBRID,
            relevantMemories = relevantMemories,
            recentMessages = recentMessages,
            recentActionContext = null,
            currentRequest = currentRequest
        )
    }

    override fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        conversationMode: ConversationMode,
        relevantMemories: List<MemoryFragment>,
        recentMessages: List<ChatMessage>,
        recentActionContext: String?,
        currentRequest: String
    ): String {
        return buildString {
            // 1. JARVIS system instructions
            append("System Instructions:\n")
            append(systemInstructions)
            
            // 2. Personality instructions
            append("\n\nPersonality Profile:\n")
            append(personalityPrompt)
            
            // 3. Conversation mode
            append("\n\nCurrent Conversation Mode: $conversationMode\n")
            
            // 4. Relevant long-term memories
            if (relevantMemories.isNotEmpty()) {
                append("\nRelevant Long-Term Memories about the User:\n")
                relevantMemories.forEach { memory ->
                    val pinIndicator = if (memory.pinned) " [Pinned]" else ""
                    append("- ${memory.content} (Category: ${memory.category})$pinIndicator\n")
                }
            }
            
            // 5. Recent conversation turns
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
            
            // 6. Recent action context
            if (!recentActionContext.isNullOrBlank()) {
                append("\nRecent Action Context:\n")
                append(recentActionContext)
                append("\n")
            }
            
            // 7. Current user message
            append("\nCurrent User Request:\n")
            append("User: $currentRequest\n")
            append("Assistant:")
        }
    }
}
