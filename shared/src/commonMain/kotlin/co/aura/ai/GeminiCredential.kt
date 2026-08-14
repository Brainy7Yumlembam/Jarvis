package co.aura.ai

enum class CredentialStatus {
    EMPTY,
    AVAILABLE,
    ACTIVE,
    COOLDOWN,
    DISABLED,
    INVALID
}

data class GeminiCredential(
    val slot: Int,
    val label: String,
    val apiKey: String?,
    val enabled: Boolean,
    val status: CredentialStatus,
    val cooldownUntil: Long? = null
)
