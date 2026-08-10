package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.actions.SendSmsAction
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class SMSPlugin : Plugin {
    override fun id(): String = "sms"
    override fun name(): String = "SMS Plugin"
    override fun description(): String = "Sends and manages device text messages."
    override fun permissions(): List<String> = listOf("android.permission.SEND_SMS", "android.permission.READ_SMS")
    override fun canHandle(action: Action): Boolean = action is SendSmsAction || action.actionType == "SEND_SMS"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "SMS action completed.")
    }
}
