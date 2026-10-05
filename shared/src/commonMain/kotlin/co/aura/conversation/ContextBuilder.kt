package co.aura.conversation

import co.aura.agent.AgentContext
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryFragment
import co.aura.domain.model.MessageSender

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

    fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        conversationMode: ConversationMode,
        relevantMemories: List<MemoryFragment>,
        recentMessages: List<ChatMessage>,
        recentActionContext: String?,
        conversationSummary: String?,
        currentRequest: String
    ): String = buildContext(
        systemInstructions = systemInstructions,
        personalityPrompt = personalityPrompt,
        conversationMode = conversationMode,
        relevantMemories = relevantMemories,
        recentMessages = recentMessages,
        recentActionContext = recentActionContext,
        currentRequest = currentRequest
    )

    fun buildContext(agentContext: AgentContext): String = buildContext(
        systemInstructions = agentContext.systemInstructions,
        personalityPrompt = agentContext.personalityPrompt ?: agentContext.systemInstructions,
        conversationMode = agentContext.conversationMode,
        relevantMemories = agentContext.selectedMemories,
        recentMessages = agentContext.selectedRecentMessages,
        recentActionContext = agentContext.recentActionContext,
        conversationSummary = agentContext.conversationSummary,
        currentRequest = agentContext.currentRequest
    )
}

class ContextBuilderImpl : ContextBuilder {

    override fun buildContext(agentContext: AgentContext): String {
        val boundedMemories = agentContext.selectedMemories.take(5)
        val boundedMessages = agentContext.selectedRecentMessages.takeLast(6)

        val prompt = buildString {
            // 1. System instructions
            append(agentContext.systemInstructions.trim())
            append("\n\n")

            // 2. Personality prompt if explicitly passed separately
            if (!agentContext.personalityPrompt.isNullOrBlank()) {
                append(agentContext.personalityPrompt.trim())
                append("\n\n")
            }

            // 3. Relevant long-term memories (only if non-empty)
            if (boundedMemories.isNotEmpty()) {
                append("Relevant Long-Term Memories:\n")
                boundedMemories.forEach { memory ->
                    val pinIndicator = if (memory.pinned) " [Pinned]" else ""
                    append("- ${memory.content} (Category: ${memory.category})$pinIndicator\n")
                }
                append("\n")
            }

            // 4. Conversation summary of older context (only if present)
            if (!agentContext.conversationSummary.isNullOrBlank()) {
                append("Conversation Summary:\n")
                append(agentContext.conversationSummary.trim())
                append("\n\n")
            }

            // 5. Recent conversation turns (bounded)
            if (boundedMessages.isNotEmpty()) {
                append("Recent Conversation History:\n")
                boundedMessages.forEach { msg ->
                    val senderName = when (msg.sender) {
                        MessageSender.USER -> "User"
                        MessageSender.ASSISTANT -> "Assistant"
                        MessageSender.SYSTEM -> "System"
                    }
                    append("$senderName: ${msg.content}\n")
                }
                append("\n")
            }

            // 6. Recent action context (only if present)
            if (!agentContext.recentActionContext.isNullOrBlank()) {
                append("Recent Action Context:\n")
                append(agentContext.recentActionContext.trim())
                append("\n\n")
            }

            // 7. Current user message
            append("Current User Request:\n")
            append("User: ${agentContext.currentRequest.trim()}\n")
            append("Assistant:")
        }

        val charCount = prompt.length
        val estimatedTokens = (charCount + 3) / 4
        AuraLogger.d(
            LogCategory.AI,
            """
            [JARVIS CONTEXT - AGENT]
            Memories: ${boundedMemories.size}
            Recent turns: ${boundedMessages.size}
            Tools: ${agentContext.selectedToolSchemas.size}
            Summary: ${if (!agentContext.conversationSummary.isNullOrBlank()) "yes" else "no"}
            Characters: $charCount
            Estimated tokens: ~$estimatedTokens
            """.trimIndent()
        )

        return prompt
    }

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
            conversationSummary = null,
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
        return buildContext(
            systemInstructions = systemInstructions,
            personalityPrompt = personalityPrompt,
            conversationMode = conversationMode,
            relevantMemories = relevantMemories,
            recentMessages = recentMessages,
            recentActionContext = recentActionContext,
            conversationSummary = null,
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
        conversationSummary: String?,
        currentRequest: String
    ): String {
        // Enforce token budget limits:
        val boundedMemories = relevantMemories.take(5)
        val boundedMessages = recentMessages.takeLast(6)

        val prompt = buildString {
            // 1. Base system instructions & personality
            append("System Instructions:\n")
            if (personalityPrompt.contains(systemInstructions)) {
                append(personalityPrompt.trim())
            } else {
                append(systemInstructions.trim())
                append("\n\n")
                append(personalityPrompt.trim())
            }

            // 2. Active Mode
            if (!personalityPrompt.contains("Current active Conversation Mode is: $conversationMode") &&
                !personalityPrompt.contains("The current active Conversation Mode is: $conversationMode")
            ) {
                append("\n\nCurrent Conversation Mode: $conversationMode\n")
            }

            // 3. Relevant long-term memories (max 3-5)
            if (boundedMemories.isNotEmpty()) {
                append("\n\nRelevant Long-Term Memories:\n")
                boundedMemories.forEach { memory ->
                    val pinIndicator = if (memory.pinned) " [Pinned]" else ""
                    append("- ${memory.content} (Category: ${memory.category})$pinIndicator\n")
                }
            }

            // 4. Conversation summary of older context (if available)
            if (!conversationSummary.isNullOrBlank()) {
                append("\nConversation Summary (Prior Context):\n")
                append(conversationSummary.trim())
                append("\n")
            }

            // 5. Recent conversation turns (bounded to last 3-6 turns)
            if (boundedMessages.isNotEmpty()) {
                append("\nRecent Conversation History:\n")
                boundedMessages.forEach { msg ->
                    val senderName = when (msg.sender) {
                        MessageSender.USER -> "User"
                        MessageSender.ASSISTANT -> "Assistant"
                        MessageSender.SYSTEM -> "System"
                    }
                    append("$senderName: ${msg.content}\n")
                }
            }

            // 6. Recent action context
            if (!recentActionContext.isNullOrBlank()) {
                append("\nRecent Action Context:\n")
                append(recentActionContext.trim())
                append("\n")
            }

            // 7. Current user message
            append("\nCurrent User Request:\n")
            append("User: ${currentRequest.trim()}\n")
            append("Assistant:")
        }

        val charCount = prompt.length
        val estimatedTokens = (charCount + 3) / 4
        AuraLogger.d(
            LogCategory.AI,
            """
            [JARVIS CONTEXT]
            Memories: ${boundedMemories.size}
            Recent turns: ${boundedMessages.size}
            Summary: ${if (!conversationSummary.isNullOrBlank()) "yes" else "no"}
            Characters: $charCount
            Estimated tokens: ~$estimatedTokens
            """.trimIndent()
        )

        return prompt
    }
}
