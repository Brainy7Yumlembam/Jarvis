package co.aura.ai

import co.aura.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GeminiCredentialManager(
    private val securityManager: SecurityManager
) {
    // Flow of currently active slot (null if none)
    private val _activeSlot = MutableStateFlow<Int?>(null)
    val activeSlot: StateFlow<Int?> = _activeSlot.asStateFlow()

    // Flow of credentials list
    private val _credentials = MutableStateFlow<List<GeminiCredential>>(emptyList())
    val credentials: StateFlow<List<GeminiCredential>> = _credentials.asStateFlow()

    // Keep in-memory cooldown times and invalid statuses.
    private val cooldowns = mutableMapOf<Int, Long>()
    private val invalidSlots = mutableSetOf<Int>()

    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val list = (1..10).map { slot ->
            val labelKey = "gemini_project_label_$slot"
            val apiKeyKey = "gemini_project_key_$slot"
            val enabledKey = "gemini_project_enabled_$slot"

            var apiKey = securityManager.getSecureToken(apiKeyKey)
            
            // Auto-migrate old "gemini_api_key" to Project 01 if Project 01 is empty
            if (slot == 1 && apiKey.isNullOrBlank()) {
                val oldKey = securityManager.getSecureToken("gemini_api_key")
                if (!oldKey.isNullOrBlank()) {
                    securityManager.saveSecureToken(apiKeyKey, oldKey)
                    apiKey = oldKey
                }
            }

            val rawLabel = securityManager.getSecureToken(labelKey)
            val isEnabled = securityManager.getSecureToken(enabledKey) != "false"

            val label = if (rawLabel.isNullOrBlank()) {
                val formattedSlot = if (slot < 10) "0$slot" else "$slot"
                "Gemini Project $formattedSlot"
            } else {
                rawLabel
            }

            val cooldownUntil = cooldowns[slot]
            val isCooldown = cooldownUntil != null && cooldownUntil > now

            val status = when {
                apiKey.isNullOrBlank() -> CredentialStatus.EMPTY
                !isEnabled -> CredentialStatus.DISABLED
                invalidSlots.contains(slot) -> CredentialStatus.INVALID
                isCooldown -> CredentialStatus.COOLDOWN
                _activeSlot.value == slot -> CredentialStatus.ACTIVE
                else -> CredentialStatus.AVAILABLE
            }

            GeminiCredential(
                slot = slot,
                label = label,
                apiKey = apiKey,
                enabled = isEnabled,
                status = status,
                cooldownUntil = if (isCooldown) cooldownUntil else null
            )
        }
        _credentials.value = list
    }

    suspend fun getBestAvailableCredential(): GeminiCredential? {
        refresh()
        val list = _credentials.value
        return list.firstOrNull { cred ->
            cred.status == CredentialStatus.AVAILABLE || cred.status == CredentialStatus.ACTIVE
        }
    }

    suspend fun setActiveSlot(slot: Int?) {
        _activeSlot.value = slot
        refresh()
    }

    suspend fun markCooldown(slot: Int, cooldownMs: Long) {
        val now = System.currentTimeMillis()
        cooldowns[slot] = now + cooldownMs
        refresh()
    }

    suspend fun markInvalid(slot: Int) {
        invalidSlots.add(slot)
        refresh()
    }

    suspend fun saveCredential(slot: Int, label: String, apiKey: String?, enabled: Boolean) {
        require(slot in 1..10) { "Invalid slot: $slot" }
        securityManager.saveSecureToken("gemini_project_label_$slot", label)
        securityManager.saveSecureToken("gemini_project_key_$slot", apiKey ?: "")
        securityManager.saveSecureToken("gemini_project_enabled_$slot", enabled.toString())
        
        // Reset invalid status and cooldown on save/edit
        invalidSlots.remove(slot)
        cooldowns.remove(slot)
        refresh()
    }

    suspend fun removeCredential(slot: Int) {
        require(slot in 1..10) { "Invalid slot: $slot" }
        securityManager.saveSecureToken("gemini_project_label_$slot", "")
        securityManager.saveSecureToken("gemini_project_key_$slot", "")
        securityManager.saveSecureToken("gemini_project_enabled_$slot", "false")
        
        // Reset invalid status and cooldown on delete
        invalidSlots.remove(slot)
        cooldowns.remove(slot)
        if (_activeSlot.value == slot) {
            _activeSlot.value = null
        }
        refresh()
    }
}
