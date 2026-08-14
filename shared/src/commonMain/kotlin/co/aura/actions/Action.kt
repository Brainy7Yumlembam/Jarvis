package co.aura.actions

import kotlinx.serialization.Serializable

@Serializable
sealed interface Action {
    val actionType: String
}

@Serializable
data class OpenAppAction(
    val appName: String
) : Action {
    override val actionType: String = "OPEN_APP"

    val packageName: String
        get() = appName
}

@Serializable
data class AlarmAction(
    val time: String
) : Action {
    override val actionType: String = "SET_ALARM"
}

@Serializable
data class CallAction(
    val phoneNumber: String
) : Action {
    override val actionType: String = "CALL"
}

@Serializable
data class SmsAction(
    val phoneNumber: String,
    val message: String
) : Action {
    override val actionType: String = "SEND_SMS"
}

@Serializable
data class SendSmsAction(
    val contactName: String,
    val message: String,
    val contact: String = contactName
) : Action {
    override val actionType: String = "SEND_SMS"
}

@Serializable
data class SetAlarmAction(
    val hour: Int,
    val minute: Int,
    val label: String? = null,
    val time: String = "$hour:$minute"
) : Action {
    override val actionType: String = "SET_ALARM"
}

@Serializable
class GetBatteryAction : Action {
    override val actionType: String = "GET_BATTERY"
}

@Serializable
class GetCurrentTimeAction : Action {
    override val actionType: String = "GET_CURRENT_TIME"
}

@Serializable
data class PlayMediaAction(
    val query: String,
    val source: String? = null,
    val targetPackage: String? = null
) : Action {
    override val actionType: String = "PLAY_MEDIA"
}

@Serializable
class PauseMediaAction : Action {
    override val actionType: String = "PAUSE_MEDIA"
}

@Serializable
class ResumeMediaAction : Action {
    override val actionType: String = "RESUME_MEDIA"
}

@Serializable
class SkipMediaAction : Action {
    override val actionType: String = "SKIP_MEDIA"
}

@Serializable
class PreviousMediaAction : Action {
    override val actionType: String = "PREVIOUS_MEDIA"
}

@Serializable
class StopMediaAction : Action {
    override val actionType: String = "STOP_MEDIA"
}

@Serializable
data class CallContactAction(
    val contactName: String
) : Action {
    override val actionType: String = "CALL_CONTACT"
}

@Serializable
data class SetTimerAction(
    val durationSeconds: Long,
    val label: String? = null
) : Action {
    override val actionType: String = "SET_TIMER"
}

@Serializable
class VolumeUpAction : Action {
    override val actionType: String = "VOLUME_UP"
}

@Serializable
class VolumeDownAction : Action {
    override val actionType: String = "VOLUME_DOWN"
}

@Serializable
data class SetVolumeAction(
    val level: Int
) : Action {
    override val actionType: String = "SET_VOLUME"
}

@Serializable
data class ToggleFlashlightAction(
    val enabled: Boolean
) : Action {
    override val actionType: String = "TOGGLE_FLASHLIGHT"
}

@Serializable
class TakeScreenshotAction : Action {
    override val actionType: String = "TAKE_SCREENSHOT"
}

@Serializable
data class SendWhatsAppAction(
    val contactName: String,
    val message: String
) : Action {
    override val actionType: String = "SEND_WHATSAPP"
}

@Serializable
data class GenericAction(
    override val actionType: String,
    val parameters: Map<String, String> = emptyMap()
) : Action
