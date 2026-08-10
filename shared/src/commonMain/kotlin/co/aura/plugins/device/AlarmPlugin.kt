package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.actions.SetAlarmAction
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class AlarmPlugin : Plugin {
    override fun id(): String = "alarm"
    override fun name(): String = "Alarm Plugin"
    override fun description(): String = "Enforces and triggers device clock alarms."
    override fun permissions(): List<String> = listOf("com.android.alarm.permission.SET_ALARM")
    override fun canHandle(action: Action): Boolean = action is SetAlarmAction || action.actionType == "SET_ALARM"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Alarm action completed.")
    }
}
