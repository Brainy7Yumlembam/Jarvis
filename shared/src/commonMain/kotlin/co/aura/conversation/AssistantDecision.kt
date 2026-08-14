package co.aura.conversation

import co.aura.actions.Action
import kotlinx.serialization.Serializable

@Serializable
data class AssistantDecision(
    val type: String, // NORMAL_CONVERSATION, ACTION, HYBRID
    val response: String,
    val actions: List<Action> = emptyList()
)
