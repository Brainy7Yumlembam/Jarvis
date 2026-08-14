package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
class ActionCommand(
    val action: Action,
    private val actionExecutor: ActionExecutor
) : Command {
    override val id: String = "cmd_${action.actionType.lowercase()}_${System.currentTimeMillis()}"

    var result: ActionResult = ActionResult.Failure("Not executed yet")

    override suspend fun execute(): Boolean {
        AuraLogger.i(LogCategory.ACTION, "Executing action command: $id")
        result = actionExecutor.executeAction(action)
        return result is ActionResult.Success
    }
}
