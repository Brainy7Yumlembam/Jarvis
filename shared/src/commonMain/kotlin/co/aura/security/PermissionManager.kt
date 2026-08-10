package co.aura.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface PermissionManager {
    fun hasPermission(permission: String): Boolean
    fun requestPermission(permission: String): Flow<Boolean>
}
