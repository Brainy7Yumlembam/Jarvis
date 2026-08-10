package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

interface ActionRouter {
    suspend fun routeAction(action: Action): Boolean
}

class ActionRouterImpl(
    private val commandBus: CommandBus
) : ActionRouter {
    override suspend fun routeAction(action: Action): Boolean {
        AuraLogger.i(LogCategory.ACTION, "Routing action: ${action.actionType}")
        
        // Mapped to a Command and executed through the Command Bus
        val command = ActionCommand(action)
        return commandBus.executeCommand(command)
    }
}
