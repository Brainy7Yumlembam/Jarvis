package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class FilesPlugin : Plugin {
    override fun id(): String = "files"
    override fun name(): String = "Files Plugin"
    override fun description(): String = "Enables files read and write."
    override fun permissions(): List<String> = emptyList()
    override fun canHandle(action: Action): Boolean = action.actionType == "FILES"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Files query performed.")
    }
}
