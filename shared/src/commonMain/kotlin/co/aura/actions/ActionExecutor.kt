package co.aura.actions

interface ActionExecutor {
    suspend fun executeAction(action: Action): ActionResult
}

class NoOpActionExecutor : ActionExecutor {
    override suspend fun executeAction(action: Action): ActionResult {
        return ActionResult.Failure("Action executor not initialized for this platform.")
    }
}
