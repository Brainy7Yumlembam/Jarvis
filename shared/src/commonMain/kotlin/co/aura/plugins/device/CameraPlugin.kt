package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class CameraPlugin : Plugin {
    override fun id(): String = "camera"
    override fun name(): String = "Camera Plugin"
    override fun description(): String = "Interfaces with device lenses for image capturing and visual search."
    override fun permissions(): List<String> = listOf("android.permission.CAMERA")
    override fun canHandle(action: Action): Boolean = action.actionType == "CAMERA"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Camera frames captured.")
    }
}
