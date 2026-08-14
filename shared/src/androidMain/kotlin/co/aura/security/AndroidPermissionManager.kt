package co.aura.security

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AndroidPermissionManager(
    private val context: Context
) : PermissionManager {
    
    override fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestPermission(permission: String): Flow<Boolean> = flow {
        emit(hasPermission(permission))
    }
}
