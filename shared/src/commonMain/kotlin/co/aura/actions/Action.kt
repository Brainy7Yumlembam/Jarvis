package co.aura.actions

import kotlinx.serialization.Serializable

@Serializable
sealed interface Action {
    val actionType: String
}

@Serializable
data class OpenAppAction(
    val packageName: String
) : Action {
    override val actionType: String = "OPEN_APP"
}

@Serializable
data class SendSmsAction(
    val contact: String,
    val message: String
) : Action {
    override val actionType: String = "SEND_SMS"
}

@Serializable
data class SetAlarmAction(
    val time: String
) : Action {
    override val actionType: String = "SET_ALARM"
}

@Serializable
data class GenericAction(
    override val actionType: String,
    val parameters: Map<String, String> = emptyMap()
) : Action
