package co.aura.router

import co.aura.actions.Action
import co.aura.actions.DeviceInfoType
import co.aura.actions.NoteOperationType
import co.aura.actions.ReminderOperationType
import co.aura.actions.SettingsPanel

/**
 * Type-safe representation of local JARVIS operations.
 */
sealed interface LocalCommand {
    data class LaunchApp(val appTarget: String, val appLabel: String? = null) : LocalCommand
    data class OpenSettings(val panel: SettingsPanel) : LocalCommand
    data class OpenUrl(val url: String) : LocalCommand
    data class WebSearch(val query: String) : LocalCommand
    data class DeviceInfo(val type: DeviceInfoType) : LocalCommand
    data class VolumeControl(val isIncrease: Boolean? = null, val level: Int? = null, val isMute: Boolean = false, val showPanel: Boolean = false) : LocalCommand
    data class TorchControl(val enabled: Boolean) : LocalCommand
    data class MediaControl(val command: String, val query: String? = null, val source: String? = null) : LocalCommand
    data class PhoneCall(val number: String? = null, val contactName: String? = null) : LocalCommand
    data class SmsMessage(val number: String? = null, val contactName: String? = null, val message: String) : LocalCommand
    data class WhatsAppMessage(val contactName: String, val message: String) : LocalCommand
    data class SendEmail(val recipient: String? = null, val subject: String? = null, val body: String? = null) : LocalCommand
    data class AlarmControl(val hour: Int, val minute: Int, val label: String? = null) : LocalCommand
    data class TimerControl(val durationSeconds: Long, val label: String? = null) : LocalCommand
    data class NoteManage(val operation: NoteOperationType, val content: String, val noteId: String? = null) : LocalCommand
    data class ReminderManage(val operation: ReminderOperationType, val title: String, val timestampMillis: Long? = null) : LocalCommand
    data class MemoryManage(val isRemember: Boolean, val isForget: Boolean, val isQuery: Boolean, val content: String) : LocalCommand
    data class ClipboardAction(val text: String, val isCopy: Boolean = true) : LocalCommand
    data class ShareAction(val text: String) : LocalCommand
    data class CameraCapture(val autoCapture: Boolean = true) : LocalCommand
    data object WakeAcknowledgement : LocalCommand
    data object VoiceStopOrCancel : LocalCommand
    data class Unsupported(val operation: String, val explanation: String, val fallbackCommand: LocalCommand? = null) : LocalCommand
}

/**
 * Result of the local command routing decision.
 */
sealed interface CommandRoutingResult {
    /**
     * Eligible for deterministic local execution (zero Gemini tokens).
     */
    data class LocalExecution(
        val command: LocalCommand,
        val requiresConfirmation: Boolean = false,
        val confirmationPrompt: String = "",
        val sensitiveOperation: Boolean = false
    ) : CommandRoutingResult

    /**
     * Command is recognized but missing necessary parameters; ask clarification locally.
     */
    data class ClarificationNeeded(
        val prompt: String,
        val expectedEntity: String
    ) : CommandRoutingResult

    /**
     * Operation is restricted or unsupported by Android; explain and offer alternative locally.
     */
    data class UnsupportedOperation(
        val explanation: String,
        val fallbackCommand: LocalCommand? = null
    ) : CommandRoutingResult

    /**
     * Direct local response string (e.g. calculation answer, wake greeting, stop, local time).
     */
    data class LocalDirectResponse(
        val responseText: String
    ) : CommandRoutingResult

    /**
     * Genuinely requires Gemini intelligence (general knowledge, explanation, reasoning, coding, creative).
     * If preExecutionCommand is present, JARVIS runs that local action first (hybrid command).
     */
    data class RequiresAi(
        val promptForGemini: String,
        val preExecutionCommand: LocalCommand? = null,
        val routingReason: String = "GENERAL_INTELLIGENCE"
    ) : CommandRoutingResult
}
