package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class WeatherPlugin : Plugin {
    override fun id(): String = "weather"
    override fun name(): String = "Weather Plugin"
    override fun description(): String = "Fetches meteorological information."
    override fun permissions(): List<String> = listOf("android.permission.ACCESS_COARSE_LOCATION")
    override fun canHandle(action: Action): Boolean = action.actionType == "WEATHER"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Weather forecast fetched.")
    }
}
