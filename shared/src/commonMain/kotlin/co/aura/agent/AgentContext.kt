package co.aura.agent

import co.aura.conversation.ConversationMode
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryFragment

/**
 * Lightweight context model containing only the minimal information required
 * for the current agent/Gemini request.
 */
data class AgentContext(
    val systemInstructions: String,
    val personalityPrompt: String? = null,
    val selectedMemories: List<MemoryFragment> = emptyList(),
    val selectedRecentMessages: List<ChatMessage> = emptyList(),
    val selectedToolSchemas: List<String> = emptyList(),
    val conversationMode: ConversationMode = ConversationMode.HYBRID,
    val recentActionContext: String? = null,
    val conversationSummary: String? = null,
    val currentRequest: String
)
