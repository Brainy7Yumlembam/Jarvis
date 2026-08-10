package co.aura.plugins

import co.aura.actions.Action
import co.aura.domain.model.ActionResult

interface Plugin {
    fun id(): String
    fun name(): String
    fun description(): String
    fun permissions(): List<String>
    fun canHandle(action: Action): Boolean
    suspend fun execute(action: Action): ActionResult
}
