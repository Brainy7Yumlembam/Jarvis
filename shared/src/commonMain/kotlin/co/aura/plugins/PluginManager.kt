package co.aura.plugins

import co.aura.actions.Action
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.ActionResult

interface PluginManager {
    fun registerPlugin(plugin: Plugin)
    fun getPlugins(): List<Plugin>
    suspend fun executeAction(action: Action): ActionResult
}

class PluginManagerImpl : PluginManager {
    private val plugins = mutableListOf<Plugin>()

    override fun registerPlugin(plugin: Plugin) {
        plugins.add(plugin)
        AuraLogger.i(LogCategory.PLUGIN, "Registered plugin: ${plugin.name()} [${plugin.id()}]")
    }

    override fun getPlugins(): List<Plugin> = plugins.toList()

    override suspend fun executeAction(action: Action): ActionResult {
        val plugin = plugins.firstOrNull { it.canHandle(action) }
        if (plugin == null) {
            AuraLogger.w(LogCategory.PLUGIN, "No plugin found to handle action: ${action.actionType}")
            return ActionResult(isSuccess = false, outputMessage = "No plugin registered for action ${action.actionType}")
        }
        
        AuraLogger.i(LogCategory.PLUGIN, "Routing action ${action.actionType} to plugin: ${plugin.name()}")
        return try {
            plugin.execute(action)
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.PLUGIN, "Error executing plugin ${plugin.id()}", e)
            ActionResult(isSuccess = false, outputMessage = "Plugin execution failed: ${e.message}")
        }
    }
}
