package co.aura.actions

interface ActionValidator {
    fun validateAction(action: Action): Boolean
}

class ActionValidatorImpl : ActionValidator {
    private val packageRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")

    override fun validateAction(action: Action): Boolean {
        return when (action) {
            is OpenAppAction -> action.appName.isNotBlank()
            is AlarmAction -> action.time.isNotBlank()
            is SetAlarmAction -> action.hour in 0..23 && action.minute in 0..59
            is CallAction -> action.phoneNumber.isNotBlank()
            is SmsAction -> action.phoneNumber.isNotBlank() && action.message.isNotBlank()
            is SendSmsAction -> action.contactName.isNotBlank() && action.message.isNotBlank()
            is PlayMediaAction -> action.query.isNotBlank()
            is PauseMediaAction -> true
            is ResumeMediaAction -> true
            is SkipMediaAction -> true
            is PreviousMediaAction -> true
            is StopMediaAction -> true
            is CallContactAction -> action.contactName.isNotBlank()
            is SetTimerAction -> action.durationSeconds > 0
            is VolumeUpAction -> true
            is VolumeDownAction -> true
            is SetVolumeAction -> action.level in 0..100
            is ToggleFlashlightAction -> true
            is TakeScreenshotAction -> true
            is SendWhatsAppAction -> action.contactName.isNotBlank() && action.message.isNotBlank()
            is GetBatteryAction -> true
            is GetCurrentTimeAction -> true
            else -> false
        }
    }
}
