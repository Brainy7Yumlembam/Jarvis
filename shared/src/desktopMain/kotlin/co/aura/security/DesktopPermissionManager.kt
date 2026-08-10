package co.aura.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DesktopPermissionManager : PermissionManager {
    override fun hasPermission(permission: String): Boolean = true
    override fun requestPermission(permission: String): Flow<Boolean> = flow { emit(true) }
}
