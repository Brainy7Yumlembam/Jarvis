package co.aura.actions

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DesktopActionExecutor : ActionExecutor {
    private val knownApps = setOf(
        "camera", "whatsapp", "youtube", "chrome", "settings", "browser", "gallery",
        "music", "spotify", "maps", "calculator", "messages", "phone", "gmail", "clock"
    )

    override suspend fun executeAction(action: Action): ActionResult {
        return when (action) {
            is OpenAppAction -> {
                val name = action.appName.trim()
                val lower = name.lowercase()
                if (knownApps.any { lower.contains(it) || it.contains(lower) }) {
                    ActionResult.Success("Opening $name, Sir.")
                } else {
                    ActionResult.Failure("Sorry, Sir. I couldn't find $name on your device.")
                }
            }
            is GetBatteryAction -> {
                ActionResult.Success("Your battery is at 100 percent, Sir.")
            }
            is GetCurrentTimeAction -> {
                val formattedTime = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                ActionResult.Success("It is $formattedTime, Sir.")
            }
            is ToggleFlashlightAction -> {
                ActionResult.Success("Flashlight turned ${if (action.enabled) "on" else "off"}, Sir.")
            }
            is VolumeUpAction -> {
                ActionResult.Success("Volume increased, Sir.")
            }
            is VolumeDownAction -> {
                ActionResult.Success("Volume decreased, Sir.")
            }
            is SetVolumeAction -> {
                ActionResult.Success("Volume set to ${action.level} percent, Sir.")
            }
            is PauseMediaAction -> {
                ActionResult.Success("Playback paused, Sir.")
            }
            is ResumeMediaAction -> {
                ActionResult.Success("Playback resumed, Sir.")
            }
            is StopMediaAction -> {
                ActionResult.Success("Playback stopped, Sir.")
            }
            is SkipMediaAction -> {
                ActionResult.Success("Skipped to next track, Sir.")
            }
            is PreviousMediaAction -> {
                ActionResult.Success("Playing previous track, Sir.")
            }
            is CallAction -> {
                ActionResult.Success("Opening dialer, Sir.")
            }
            is SmsAction -> {
                ActionResult.Success("Opening SMS composer, Sir.")
            }
            is SendWhatsAppAction -> {
                ActionResult.Success("Opening WhatsApp for ${action.contactName}, Sir.")
            }
            is OpenSettingsAction -> {
                ActionResult.Success("Opening ${action.panel.name.lowercase()} settings, Sir.")
            }
            is OpenUrlAction -> {
                ActionResult.Success("Opening ${action.url}, Sir.")
            }
            is WebSearchAction -> {
                ActionResult.Success("Searching for \"${action.query}\", Sir.")
            }
            is SendEmailAction -> {
                ActionResult.Success("Opening email composer, Sir.")
            }
            is ShareTextAction -> {
                ActionResult.Success("Sharing text, Sir.")
            }
            is CopyToClipboardAction -> {
                ActionResult.Success("Copied to clipboard, Sir.")
            }
            is GetDeviceInfoAction -> {
                when (action.infoType) {
                    DeviceInfoType.DATE -> ActionResult.Success("Today is " + SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date()) + ", Sir.")
                    DeviceInfoType.TIME -> ActionResult.Success("It is " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + ", Sir.")
                    DeviceInfoType.BATTERY -> ActionResult.Success("Your battery is at 100 percent, Sir.")
                    DeviceInfoType.CHARGING -> ActionResult.Success("Device is connected to AC power, Sir.")
                    DeviceInfoType.ANDROID_VERSION -> ActionResult.Success("Desktop environment running JVM ${System.getProperty("java.version")}, Sir.")
                    DeviceInfoType.STORAGE -> ActionResult.Success("Storage space is abundant on desktop, Sir.")
                    DeviceInfoType.NETWORK_CONNECTIVITY -> ActionResult.Success("Internet connection is active, Sir.")
                    DeviceInfoType.WIFI_STATUS -> ActionResult.Success("Wi-Fi is connected, Sir.")
                    DeviceInfoType.BLUETOOTH_STATUS -> ActionResult.Success("Bluetooth is enabled, Sir.")
                    DeviceInfoType.FLASHLIGHT_STATUS -> ActionResult.Success("Flashlight is unavailable on desktop, Sir.")
                }
            }
            is MuteVolumeAction -> {
                ActionResult.Success("Audio muted, Sir.")
            }
            is ShowVolumePanelAction -> {
                ActionResult.Success("Opening volume controls, Sir.")
            }
            is ManageNoteAction -> {
                when (action.operation) {
                    NoteOperationType.CREATE -> ActionResult.Success("Note saved: \"${action.content}\", Sir.")
                    NoteOperationType.READ_ALL -> ActionResult.Success("Here are your saved notes, Sir.")
                    NoteOperationType.SEARCH -> ActionResult.Success("Searching notes for \"${action.content}\", Sir.")
                    NoteOperationType.DELETE -> ActionResult.Success("Note deleted, Sir.")
                }
            }
            is ManageReminderAction -> {
                ActionResult.Success("Reminder action completed, Sir.")
            }
            is CameraCaptureAction -> {
                ActionResult.Success("Photo captured and saved to gallery, Sir.")
            }
            else -> {
                ActionResult.Failure("Sir, that action is not supported on this platform.")
            }
        }
    }
}
