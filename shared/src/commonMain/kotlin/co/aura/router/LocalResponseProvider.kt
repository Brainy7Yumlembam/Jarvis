package co.aura.router

/**
 * Provides authoritative, concise, polite natural language responses for JARVIS local actions.
 * Consistently maintains the JARVIS persona ("At your service, sir.", etc.).
 */
object LocalResponseProvider {

    fun wakeGreeting(): String {
        return "Yes, sir?"
    }

    fun voiceStop(): String {
        return "Stopped. Standing by, sir."
    }

    fun appLaunchSuccess(appName: String): String {
        return "Opening $appName, sir."
    }

    fun appLaunchFailure(appName: String): String {
        return "Sorry, sir. I couldn't find $appName on your device."
    }

    fun settingsOpen(panelName: String): String {
        return "Opening $panelName settings, sir."
    }

    fun torchEnabled(enabled: Boolean): String {
        return if (enabled) "Flashlight on, sir." else "Flashlight off, sir."
    }

    fun volumeAdjusted(increase: Boolean): String {
        return if (increase) "Volume increased, sir." else "Volume decreased, sir."
    }

    fun volumeSet(level: Int): String {
        return "Volume set to $level percent, sir."
    }

    fun volumeMuted(): String {
        return "Audio muted, sir."
    }

    fun mediaPlayback(actionDesc: String): String {
        return "$actionDesc, sir."
    }

    fun copiedToClipboard(): String {
        return "Copied to clipboard, sir."
    }

    fun webSearchInitiated(query: String): String {
        return "Searching for \"$query\", sir."
    }

    fun urlOpened(url: String): String {
        return "Opening link, sir."
    }

    fun alarmSet(timeDisplay: String, label: String?): String {
        return if (label.isNullOrBlank()) "Alarm set for $timeDisplay, sir." else "Alarm set for $timeDisplay for $label, sir."
    }

    fun timerSet(durationDisplay: String, label: String?): String {
        return if (label.isNullOrBlank()) "Timer set for $durationDisplay, sir." else "Timer set for $durationDisplay for $label, sir."
    }

    fun callDialerOpened(target: String): String {
        return "Opening dialer for $target, sir."
    }

    fun callDirectInitiated(target: String): String {
        return "Calling $target now, sir."
    }

    fun messageComposerOpened(target: String): String {
        return "Opening message composer for $target, sir."
    }

    fun whatsAppOpened(target: String): String {
        return "Opening WhatsApp for $target, sir."
    }

    fun emailComposerOpened(recipient: String?): String {
        return if (!recipient.isNullOrBlank()) "Opening email draft for $recipient, sir." else "Opening email composer, sir."
    }

    fun noteSaved(preview: String): String {
        return "Note saved: \"$preview\", sir."
    }

    fun noteDeleted(preview: String): String {
        return "Note deleted: \"$preview\", sir."
    }

    fun preferenceSaved(content: String): String {
        return "I will remember: $content, sir."
    }

    fun preferenceForgotten(content: String): String {
        return "I have forgotten that preference, sir."
    }

    fun unsupportedRestriction(operation: String, alternative: String): String {
        return "Sir, Android restricts third-party applications from directly $operation. $alternative"
    }

    fun clarificationNeeded(prompt: String): String {
        return prompt
    }

    fun genericSuccess(): String {
        return "Action completed, sir."
    }

    fun genericFailure(reason: String? = null): String {
        return if (!reason.isNullOrBlank()) reason else "Sir, the requested action could not be completed."
    }
}
