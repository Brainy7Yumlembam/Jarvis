package co.aura.actions

class DesktopActionExecutor : ActionExecutor {
    override suspend fun executeAction(action: Action): ActionResult {
        return when (action) {
            is OpenAppAction -> {
                ActionResult.Success("Opened app ${action.packageName} on Desktop")
            }
            is GetBatteryAction -> {
                ActionResult.Success("Desktop battery level is 100%")
            }
            is GetCurrentTimeAction -> {
                ActionResult.Success("Current Desktop time is 12:00 PM")
            }
            else -> {
                ActionResult.Failure("Unsupported action on Desktop")
            }
        }
    }
}
