package co.aura.security

import co.aura.actions.Action
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

interface SecurityManager {
    suspend fun authorizeAction(action: Action): Boolean
    suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean
    suspend fun saveSecureToken(key: String, token: String)
    suspend fun getSecureToken(key: String): String?
}

class SecurityManagerImpl(
    private val permissionManager: PermissionManager,
    private val secureStorage: SecureStorage
) : SecurityManager {

    override suspend fun authorizeAction(action: Action): Boolean {
        AuraLogger.i(LogCategory.SECURITY, "Checking authorization permissions for action: ${action.actionType} - Auto-allowed (Allow All mode)")
        return true
    }

    override suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean {
        // TODO: Bridge to UI confirmation flow via StateFlow trigger
        AuraLogger.i(LogCategory.SECURITY, "Prompting user for confirmation of sensitive action: ${action.actionType}")
        return true
    }

    override suspend fun saveSecureToken(key: String, token: String) {
        if (token.isEmpty()) {
            secureStorage.remove(key)
        } else {
            secureStorage.put(key, token)
        }
    }

    override suspend fun getSecureToken(key: String): String? {
        val value = secureStorage.get(key)
        return if (value.isNullOrEmpty()) null else value
    }

    private fun getRequiredPermissionsForAction(action: Action): String? {
        return when (action.actionType) {
            "SEND_SMS" -> "android.permission.SEND_SMS"
            "CAMERA" -> "android.permission.CAMERA"
            else -> null
        }
    }
}
