package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

interface ActionRouter {
    suspend fun routeAction(action: Action): ActionResult
}

class ActionRouterImpl(
    private val commandBus: CommandBus,
    private val actionExecutor: ActionExecutor
) : ActionRouter {
    override suspend fun routeAction(action: Action): ActionResult {
        AuraLogger.i(LogCategory.ACTION, "Routing action: ${action.actionType}")
        
        val command = ActionCommand(action, actionExecutor)
        commandBus.executeCommand(command)
        return command.result
    }
}
