package co.aura.domain.repository

import co.aura.domain.model.ActionResult
import co.aura.domain.model.DeviceAction
import co.aura.domain.model.DeviceCapability

interface DeviceActionRepository {
    suspend fun executeSystemAction(action: DeviceAction): ActionResult
    fun getDeviceCapabilities(): List<DeviceCapability>
}
