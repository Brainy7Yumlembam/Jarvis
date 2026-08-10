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
    private val permissionManager: PermissionManager
) : SecurityManager {
    private val secureStorage = mutableMapOf<String, String>()

    override suspend fun authorizeAction(action: Action): Boolean {
        AuraLogger.i(LogCategory.SECURITY, "Checking authorization permissions for action: ${action.actionType}")
        
        // Mapped verification
        val requiredPermission = getRequiredPermissionsForAction(action)
        if (requiredPermission != null && !permissionManager.hasPermission(requiredPermission)) {
            AuraLogger.w(LogCategory.SECURITY, "Authorization failed. Missing: $requiredPermission")
            return false
        }
        
        return true
    }

    override suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean {
        // TODO: Bridge to UI confirmation flow via StateFlow trigger
        AuraLogger.i(LogCategory.SECURITY, "Prompting user for confirmation of sensitive action: ${action.actionType}")
        return true
    }

    override suspend fun saveSecureToken(key: String, token: String) {
        // TODO: Interface to platform encrypted preferences (Android KeyStore)
        secureStorage[key] = token
    }

    override suspend fun getSecureToken(key: String): String? {
        return secureStorage[key]
    }

    private fun getRequiredPermissionsForAction(action: Action): String? {
        return when (action.actionType) {
            "SEND_SMS" -> "android.permission.SEND_SMS"
            "CAMERA" -> "android.permission.CAMERA"
            else -> null
        }
    }
}
