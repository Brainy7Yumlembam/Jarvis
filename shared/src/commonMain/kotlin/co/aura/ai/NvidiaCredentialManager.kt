package co.aura.ai

import co.aura.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages configuration, secure storage, and live availability tracking
 * for NVIDIA NIM AI services.
 */
class NvidiaCredentialManager(
    private val securityManager: SecurityManager
) {
    companion object {
        const val KEY_API_KEY = "nvidia_api_key"
        const val KEY_MODEL = "nvidia_model"
        const val KEY_BASE_URL = "nvidia_base_url"
        const val KEY_ENABLED = "nvidia_enabled"

        const val DEFAULT_MODEL = "meta/llama-3.1-8b-instruct"
        const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    }

    private val _status = MutableStateFlow(CredentialStatus.EMPTY)
    val status: StateFlow<CredentialStatus> = _status.asStateFlow()

    @Volatile
    private var cooldownUntil: Long? = null

    @Volatile
    private var isInvalidFlag: Boolean = false

    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val key = getApiKey()
        val enabled = isEnabled()

        val isCooldown = cooldownUntil != null && cooldownUntil!! > now

        val newStatus = when {
            key.isNullOrBlank() -> CredentialStatus.EMPTY
            !enabled -> CredentialStatus.DISABLED
            isInvalidFlag -> CredentialStatus.INVALID
            isCooldown -> CredentialStatus.COOLDOWN
            else -> CredentialStatus.AVAILABLE
        }
        _status.value = newStatus
    }

    suspend fun getApiKey(): String? {
        return securityManager.getSecureToken(KEY_API_KEY)?.trim()?.takeIf { it.isNotBlank() }
    }

    suspend fun saveApiKey(apiKey: String) {
        securityManager.saveSecureToken(KEY_API_KEY, apiKey.trim())
        isInvalidFlag = false
        cooldownUntil = null
        refresh()
    }

    suspend fun clearApiKey() {
        securityManager.saveSecureToken(KEY_API_KEY, "")
        isInvalidFlag = false
        cooldownUntil = null
        refresh()
    }

    suspend fun getModel(): String {
        return securityManager.getSecureToken(KEY_MODEL)?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL
    }

    suspend fun saveModel(model: String) {
        securityManager.saveSecureToken(KEY_MODEL, model.trim())
    }

    suspend fun getBaseUrl(): String {
        val url = securityManager.getSecureToken(KEY_BASE_URL)?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_URL
        return url.removeSuffix("/")
    }

    suspend fun saveBaseUrl(baseUrl: String) {
        securityManager.saveSecureToken(KEY_BASE_URL, baseUrl.trim().removeSuffix("/"))
    }

    suspend fun isEnabled(): Boolean {
        return securityManager.getSecureToken(KEY_ENABLED) != "false"
    }

    suspend fun setEnabled(enabled: Boolean) {
        securityManager.saveSecureToken(KEY_ENABLED, enabled.toString())
        refresh()
    }

    fun markCooldown(cooldownMs: Long = 300000L) {
        cooldownUntil = System.currentTimeMillis() + cooldownMs
        _status.value = CredentialStatus.COOLDOWN
    }

    fun markInvalid() {
        isInvalidFlag = true
        _status.value = CredentialStatus.INVALID
    }

    fun resetCooldown() {
        cooldownUntil = null
        isInvalidFlag = false
    }

    suspend fun isAvailable(): Boolean {
        refresh()
        return _status.value == CredentialStatus.AVAILABLE
    }
}
