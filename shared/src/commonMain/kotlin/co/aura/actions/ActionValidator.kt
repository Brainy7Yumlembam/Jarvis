package co.aura.actions

interface ActionValidator {
    fun validateAction(action: Action): Boolean
}

class ActionValidatorImpl : ActionValidator {
    override fun validateAction(action: Action): Boolean {
        return when (action) {
            is OpenAppAction -> action.packageName.isNotBlank()
            is SendSmsAction -> action.contact.isNotBlank() && action.message.isNotBlank()
            is SetAlarmAction -> action.time.isNotBlank() && action.time.contains(":")
            is GenericAction -> action.actionType.isNotBlank()
        }
    }
}
