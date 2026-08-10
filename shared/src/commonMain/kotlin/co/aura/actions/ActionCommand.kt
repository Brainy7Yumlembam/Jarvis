package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

class ActionCommand(
    val action: Action
) : Command {
    override val id: String = "cmd_${action.actionType.lowercase()}_${System.currentTimeMillis()}"

    override suspend fun execute(): Boolean {
        AuraLogger.i(LogCategory.ACTION, "Executing action command: $id")
        // TODO: In the future, this delegates to co.aura.plugins.PluginManager.executePlugin(action)
        return true
    }
}
