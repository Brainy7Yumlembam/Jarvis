package co.aura.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CognitiveIntent(
    val name: String,
    val confidence: Float,
    val parameters: Map<String, String>
)
