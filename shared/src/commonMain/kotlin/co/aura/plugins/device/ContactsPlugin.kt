package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class ContactsPlugin : Plugin {
    override fun id(): String = "contacts"
    override fun name(): String = "Contacts Plugin"
    override fun description(): String = "Provides access to system contacts book."
    override fun permissions(): List<String> = listOf("android.permission.READ_CONTACTS")
    override fun canHandle(action: Action): Boolean = action.actionType == "CONTACTS"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Contacts query executed.")
    }
}
