package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.actions.OpenAppAction
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class BrowserPlugin : Plugin {
    override fun id(): String = "browser"
    override fun name(): String = "Browser Plugin"
    override fun description(): String = "Opens URL routes and web pages."
    override fun permissions(): List<String> = emptyList()
    
    override fun canHandle(action: Action): Boolean {
        if (action is OpenAppAction && action.packageName.contains("chrome")) return true
        return action.actionType == "BROWSER"
    }

    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Browser query handled.")
    }
}
