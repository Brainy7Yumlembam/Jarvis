package co.aura.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ActionType {
    VOLUME, BRIGHTNESS, NOTIFICATION, SMS, CALENDAR, CONTACTS, CAMERA, OCR, MUSIC
}

@Serializable
data class DeviceAction(
    val type: ActionType,
    val parameters: Map<String, String>
)

@Serializable
data class ActionResult(
    val isSuccess: Boolean,
    val outputMessage: String,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class DeviceCapability(
    val type: ActionType,
    val isAvailable: Boolean
)
