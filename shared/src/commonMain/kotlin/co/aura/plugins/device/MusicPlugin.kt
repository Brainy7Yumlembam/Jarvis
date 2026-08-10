package co.aura.plugins.device

import co.aura.actions.Action
import co.aura.domain.model.ActionResult
import co.aura.plugins.Plugin

class MusicPlugin : Plugin {
    override fun id(): String = "music"
    override fun name(): String = "Music Plugin"
    override fun description(): String = "Interfaces with playback systems and media APIs."
    override fun permissions(): List<String> = emptyList()
    override fun canHandle(action: Action): Boolean = action.actionType == "MUSIC"
    override suspend fun execute(action: Action): ActionResult {
        return ActionResult(isSuccess = true, outputMessage = "Music playback changed.")
    }
}
