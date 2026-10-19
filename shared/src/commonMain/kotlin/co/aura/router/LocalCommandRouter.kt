package co.aura.router

import co.aura.actions.DeviceInfoType
import co.aura.actions.NoteOperationType
import co.aura.actions.ReminderOperationType
import co.aura.actions.SettingsPanel

/**
 * Deterministic local command router and classifier.
 *
 * Core rule: If a task can be handled locally via Android APIs, Kotlin code, local storage,
 * or installed applications, it is resolved locally with ZERO Gemini token consumption.
 *
 * Distinguishes:
 * - Supported local commands
 * - Ambiguous commands that require local clarification
 * - Unsupported or restricted operations with safe alternatives
 * - Hybrid commands (local operation + Gemini reasoning)
 * - Questions requiring genuine AI intelligence
 */
object LocalCommandRouter {

    private val fillerPrefixes = listOf(
        "hey jarvis please ", "ok jarvis please ", "okay jarvis please ",
        "jarvis please ", "aura please ", "hey jarvis ", "ok jarvis ",
        "okay jarvis ", "jarvis ", "aura ", "please ", "can you please ",
        "could you please ", "would you please ", "can you ", "could you ",
        "would you ", "kindly "
    )

    fun cleanInput(rawInput: String): String {
        var text = rawInput.trim()
        val lower = text.lowercase()

        for (prefix in fillerPrefixes) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }
        return text.removeSuffix(".").removeSuffix("?").removeSuffix("!").trim()
    }

    fun route(rawInput: String): CommandRoutingResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return CommandRoutingResult.LocalDirectResponse("Standing by, sir.")
        }

        val cleaned = cleanInput(trimmed)
        val lower = cleaned.lowercase()

        // ── 1. Wake word acknowledgement ─────────────────────────────────────
        if (lower == "wake up" || lower == "wake up jarvis" || lower == "hey jarvis") {
            return CommandRoutingResult.LocalDirectResponse("At your service, sir. Online and ready.")
        }

        // ── 2. Local Stop / Cancel / Voice control ───────────────────────────
        if (lower == "stop" || lower == "cancel" || lower == "shut up" ||
            lower == "never mind" || lower == "nevermind" || lower == "wait" ||
            lower == "hold on" || lower == "be quiet" || lower == "abort"
        ) {
            return CommandRoutingResult.LocalDirectResponse(LocalResponseProvider.voiceStop())
        }

        // ── 2b. Token Usage & Cost Report ────────────────────────────────────
        if (lower == "token usage" || lower == "show token usage" || lower == "tokens used" ||
            lower == "how many tokens" || lower == "how many tokens have i used" || lower == "token report" ||
            lower == "token consumption" || lower == "show tokens"
        ) {
            return CommandRoutingResult.LocalDirectResponse(co.aura.ai.TokenUsageTracker.generateDebugReport())
        }

        // ── 3. Hybrid Command Check ("Open <app> and <question>") ─────────────
        val hybridResult = checkForHybridCommand(cleaned, lower)
        if (hybridResult != null) {
            return hybridResult
        }

        // ── 4. Calculator & Safe Math / Unit Conversion / Date Diff ──────────
        if (LocalCalculator.canHandle(cleaned)) {
            val calcResult = LocalCalculator.evaluate(cleaned)
            if (calcResult != null) {
                return CommandRoutingResult.LocalDirectResponse(calcResult)
            }
        }

        // ── 5. Date & Time ───────────────────────────────────────────────────
        val timePhrases = listOf(
            "what time is it", "what is the time", "whats the time", "tell me the time",
            "current time", "the time", "time right now", "time"
        )
        if (timePhrases.any { lower == it || lower == "what is $it" }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.TIME))
        }

        val datePhrases = listOf(
            "what is the date", "what is today's date", "whats todays date", "whats the date",
            "today's date", "todays date", "what day is today", "what day is it", "current date", "what date is today"
        )
        if (datePhrases.any { lower == it || lower == "what is $it" }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.DATE))
        }

        // ── 6. Basic Device Info & Status ────────────────────────────────────
        val batteryPhrases = listOf(
            "what is the battery", "what is my battery", "battery percentage", "battery level",
            "show battery", "show my battery", "check battery", "how much battery", "battery",
            "what is my battery level", "what is the battery level", "check my battery level"
        )
        if (batteryPhrases.any { lower == it || lower == "check $it" || lower == "what is $it" } ||
            (lower.contains("battery") && (lower.contains("level") || lower.contains("percentage") || lower.contains("status") || lower.contains("how much") || lower.contains("what is")))) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.BATTERY))
        }

        val chargingPhrases = listOf(
            "is it charging", "is the phone charging", "is my phone charging",
            "charging status", "is device charging", "am i charging"
        )
        if (chargingPhrases.any { lower == it }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.CHARGING))
        }

        val androidVersionPhrases = listOf(
            "what is the android version", "what is my android version", "android version",
            "what version of android", "os version", "what os version"
        )
        if (androidVersionPhrases.any { lower == it }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.ANDROID_VERSION))
        }

        val storagePhrases = listOf(
            "how much storage do i have", "how much storage", "available storage",
            "storage space", "free storage", "storage remaining", "storage status",
            "how much storage is left", "storage left"
        )
        if (storagePhrases.any { lower == it } || (lower.contains("storage") && (lower.contains("left") || lower.contains("how much") || lower.contains("available") || lower.contains("free") || lower.contains("remaining")))) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.STORAGE))
        }

        val connectivityPhrases = listOf(
            "am i connected to the internet", "am i connected to internet", "is internet available",
            "internet status", "connection status", "network status", "do i have internet"
        )
        if (connectivityPhrases.any { lower == it }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.NETWORK_CONNECTIVITY))
        }

        val wifiStatusPhrases = listOf(
            "is wifi connected", "is wi-fi connected", "wifi status", "wi-fi status"
        )
        if (wifiStatusPhrases.any { lower == it }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.WIFI_STATUS))
        }

        val bluetoothStatusPhrases = listOf(
            "is bluetooth on", "is bluetooth enabled", "bluetooth status"
        )
        if (bluetoothStatusPhrases.any { lower == it }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.BLUETOOTH_STATUS))
        }

        val torchStatusPhrases = listOf(
            "is flashlight on", "is torch on", "flashlight status", "torch status"
        )
        if (torchStatusPhrases.any { lower == it }) {
            return CommandRoutingResult.LocalExecution(LocalCommand.DeviceInfo(DeviceInfoType.FLASHLIGHT_STATUS))
        }

        // ── 7. Flashlight / Torch Controls ───────────────────────────────────
        if (lower == "turn on flashlight" || lower == "flashlight on" || lower == "torch on" ||
            lower == "turn on the flashlight" || lower == "turn on torch" || lower == "enable flashlight"
        ) {
            return CommandRoutingResult.LocalExecution(LocalCommand.TorchControl(true))
        }
        if (lower == "turn off flashlight" || lower == "flashlight off" || lower == "torch off" ||
            lower == "turn off the flashlight" || lower == "turn off torch" || lower == "disable flashlight"
        ) {
            return CommandRoutingResult.LocalExecution(LocalCommand.TorchControl(false))
        }

        // ── 8. Sound & Volume Controls ───────────────────────────────────────
        if (lower == "volume up" || lower == "louder" || lower == "turn up volume" ||
            lower == "turn up the volume" || lower == "increase volume" || lower == "raise volume"
        ) {
            return CommandRoutingResult.LocalExecution(LocalCommand.VolumeControl(isIncrease = true))
        }
        if (lower == "volume down" || lower == "quieter" || lower == "turn down volume" ||
            lower == "turn down the volume" || lower == "decrease volume" || lower == "lower volume"
        ) {
            return CommandRoutingResult.LocalExecution(LocalCommand.VolumeControl(isIncrease = false))
        }
        if (lower == "mute" || lower == "mute audio" || lower == "mute volume" || lower == "silence audio") {
            return CommandRoutingResult.LocalExecution(LocalCommand.VolumeControl(isMute = true))
        }
        if (lower in listOf("volume panel", "open volume panel", "volume settings", "show volume slider", "show volume panel", "volume slider")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.VolumeControl(showPanel = true))
        }
        val setVolumeMatch = Regex("""(?:set\s+)?volume\s+(?:to\s+)?(\d+)\s*%?""").find(lower)
        if (setVolumeMatch != null) {
            val level = setVolumeMatch.groupValues[1].toIntOrNull() ?: 50
            return CommandRoutingResult.LocalExecution(LocalCommand.VolumeControl(level = level))
        }

        // ── 9. Screen Brightness (Restricted direct toggle, explain & open settings) ──
        if (lower.contains("brightness") || lower == "screen brightness" || lower == "turn up brightness" || lower == "turn down brightness") {
            return CommandRoutingResult.UnsupportedOperation(
                explanation = "Sir, Android restricts third-party applications from directly changing screen brightness without system write settings permission. Opening display settings instead.",
                fallbackCommand = LocalCommand.OpenSettings(SettingsPanel.DISPLAY)
            )
        }

        // ── 10. Restricted Connectivity Toggles (Explain & Open relevant panel) ─
        if (lower in listOf("turn on wifi", "turn off wifi", "toggle wifi", "enable wifi", "disable wifi")) {
            return CommandRoutingResult.UnsupportedOperation(
                explanation = "Sir, Android restrictions prevent third-party applications from directly toggling Wi-Fi. Opening Wi-Fi settings for you.",
                fallbackCommand = LocalCommand.OpenSettings(SettingsPanel.WIFI)
            )
        }
        if (lower in listOf("turn on bluetooth", "turn off bluetooth", "toggle bluetooth", "enable bluetooth", "disable bluetooth")) {
            return CommandRoutingResult.UnsupportedOperation(
                explanation = "Sir, Android requires system settings to manage Bluetooth state. Opening Bluetooth settings for you.",
                fallbackCommand = LocalCommand.OpenSettings(SettingsPanel.BLUETOOTH)
            )
        }
        if (lower in listOf("turn on mobile data", "turn off mobile data", "toggle mobile data", "enable cellular data", "disable mobile data")) {
            return CommandRoutingResult.UnsupportedOperation(
                explanation = "Sir, Android restricts direct mobile data toggling for third-party applications. Opening network settings for you.",
                fallbackCommand = LocalCommand.OpenSettings(SettingsPanel.NETWORK)
            )
        }
        if (lower in listOf("turn on hotspot", "turn off hotspot", "enable hotspot", "disable hotspot")) {
            return CommandRoutingResult.UnsupportedOperation(
                explanation = "Sir, Android restricts third-party applications from directly toggling the hotspot. Opening hotspot settings for you.",
                fallbackCommand = LocalCommand.OpenSettings(SettingsPanel.HOTSPOT)
            )
        }

        // ── 11. Settings Subpanels ───────────────────────────────────────────
        if (lower in listOf("open wifi settings", "wifi settings", "wi-fi settings", "open wi-fi settings")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.WIFI))
        }
        if (lower in listOf("open bluetooth settings", "bluetooth settings")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.BLUETOOTH))
        }
        if (lower in listOf("open location settings", "location settings", "gps settings")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.LOCATION))
        }
        if (lower in listOf("open display settings", "display settings", "screen settings")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.DISPLAY))
        }
        if (lower in listOf("open sound settings", "sound settings", "audio settings", "sound and vibration")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.SOUND))
        }
        if (lower in listOf("open battery settings", "battery settings", "battery saver settings", "battery optimization")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.BATTERY))
        }
        if (lower in listOf("open app settings", "jarvis settings", "application settings", "app permissions")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.APPS))
        }
        if (lower in listOf("open hotspot settings", "hotspot settings", "tethering settings")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.HOTSPOT))
        }
        if (lower in listOf("open settings", "phone settings", "system settings", "settings")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenSettings(SettingsPanel.MAIN))
        }

        // ── 12. Web Search & URLs ────────────────────────────────────────────
        if (lower.startsWith("open http://") || lower.startsWith("open https://") ||
            lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")
        ) {
            val url = if (lower.startsWith("open ")) cleaned.substring(5).trim() else cleaned
            val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            return CommandRoutingResult.LocalExecution(LocalCommand.OpenUrl(fullUrl))
        }
        val searchPrefixes = listOf("search google for ", "search the web for ", "google ")
        for (prefix in searchPrefixes) {
            if (lower.startsWith(prefix)) {
                val query = cleaned.substring(prefix.length).trim()
                if (query.isNotBlank()) {
                    return CommandRoutingResult.LocalExecution(LocalCommand.WebSearch(query))
                } else {
                    return CommandRoutingResult.ClarificationNeeded("What would you like me to search for, sir?", "search_query")
                }
            }
        }

        // ── 13. App Launching & Common Applications ──────────────────────────
        val appRouting = routeAppLaunching(cleaned, lower)
        if (appRouting != null) {
            return appRouting
        }

        // ── 14. Calls, SMS, WhatsApp, Email ──────────────────────────────────
        val commRouting = routeCommunication(cleaned, lower)
        if (commRouting != null) {
            return commRouting
        }

        // ── 15. Alarms & Timers ──────────────────────────────────────────────
        val alarmTimerRouting = routeAlarmsAndTimers(cleaned, lower)
        if (alarmTimerRouting != null) {
            return alarmTimerRouting
        }

        // ── 16. Notes & Reminders ────────────────────────────────────────────
        val noteReminderRouting = routeNotesAndReminders(cleaned, lower)
        if (noteReminderRouting != null) {
            return noteReminderRouting
        }

        // ── 18. Clipboard & Sharing ──────────────────────────────────────────
        if (lower.startsWith("copy ") && (lower.endsWith(" to clipboard") || lower.contains(" to the clipboard"))) {
            val textToCopy = cleaned.removePrefix("copy ").removePrefix("Copy ")
                .replace(Regex("""(?i)\s+to\s+(?:the\s+)?clipboard$"""), "").trim()
            if (textToCopy.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(LocalCommand.ClipboardAction(textToCopy, isCopy = true))
            }
        }
        if (lower.startsWith("share ") || lower.startsWith("share this ")) {
            val textToShare = cleaned.removePrefix("share this ").removePrefix("share ").trim()
            if (textToShare.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(LocalCommand.ShareAction(textToShare))
            }
        }

        // ── 19. Media Playback Controls ──────────────────────────────────────
        val mediaRouting = routeMedia(cleaned, lower)
        if (mediaRouting != null) {
            return mediaRouting
        }

        // ── 20. Ambiguity Checks ─────────────────────────────────────────────
        if (lower == "open" || lower == "launch") {
            return CommandRoutingResult.ClarificationNeeded("Which application would you like me to open, sir?", "app_name")
        }
        if (lower == "call" || lower == "dial" || lower == "make a call") {
            return CommandRoutingResult.ClarificationNeeded("Who would you like me to call, sir?", "contact_or_number")
        }
        if (lower == "text" || lower == "send sms" || lower == "message" || lower == "send a message") {
            return CommandRoutingResult.ClarificationNeeded("Who would you like to message, and what should it say, sir?", "message_details")
        }
        if (lower == "whatsapp" || lower == "send whatsapp") {
            return CommandRoutingResult.ClarificationNeeded("Who would you like to message on WhatsApp, sir?", "contact_name")
        }
        if (lower == "email" || lower == "send email") {
            return CommandRoutingResult.ClarificationNeeded("Who should I email and what is the message, sir?", "email_details")
        }
        if (lower == "calculate") {
            return CommandRoutingResult.ClarificationNeeded("What calculation would you like me to perform, sir?", "math_expression")
        }
        if (lower == "set timer" || lower == "timer") {
            return CommandRoutingResult.ClarificationNeeded("For how long should I set the timer, sir?", "timer_duration")
        }
        if (lower == "set alarm" || lower == "alarm") {
            return CommandRoutingResult.ClarificationNeeded("For what time should I set the alarm, sir?", "alarm_time")
        }
        if (lower == "take note" || lower == "add note" || lower == "create note") {
            return CommandRoutingResult.ClarificationNeeded("What would you like the note to say, sir?", "note_content")
        }

        // ── 21. Route to Gemini ──────────────────────────────────────────────
        // All tasks genuinely requiring AI generation, reasoning, coding, explanation, summarization, etc.
        val reason = classifyGeminiReason(lower)
        return CommandRoutingResult.RequiresAi(
            promptForGemini = rawInput.trim(),
            preExecutionCommand = null,
            routingReason = reason
        )
    }

    private fun checkForHybridCommand(cleaned: String, lower: String): CommandRoutingResult? {
        val splitRegex = Regex("""^(?:open|launch)\s+([a-zA-Z0-9\s]+?)\s+(?:and|then)\s+(explain\b.*|tell me\b.*|how\b.*|why\b.*|compare\b.*|write\b.*|give me ideas\b.*)$""", RegexOption.IGNORE_CASE)
        val match = splitRegex.find(cleaned) ?: return null

        val appTarget = match.groupValues[1].trim()
        val question = match.groupValues[2].trim()

        if (appTarget.isNotBlank() && question.isNotBlank()) {
            val targetPkg = if (appTarget.equals("youtube", ignoreCase = true)) "com.google.android.youtube"
            else if (appTarget.equals("whatsapp", ignoreCase = true)) "com.whatsapp"
            else appTarget
            return CommandRoutingResult.RequiresAi(
                promptForGemini = question,
                preExecutionCommand = LocalCommand.LaunchApp(targetPkg),
                routingReason = "HYBRID_LOCAL_APP_AND_REASONING"
            )
        }
        return null
    }

    private fun routeAppLaunching(cleaned: String, lower: String): CommandRoutingResult? {
        // Specific app triggers
        when {
            lower in listOf(
                "take a photo", "take photo", "take a picture", "take picture",
                "capture photo", "capture image", "capture picture", "snap photo",
                "snap a photo", "snap picture", "snap a picture", "click a photo", "click a picture"
            ) ->
                return CommandRoutingResult.LocalExecution(LocalCommand.CameraCapture(autoCapture = true))
            lower in listOf("open camera", "launch camera", "start camera") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("camera", "Camera"))
            lower in listOf("open whatsapp", "launch whatsapp") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("com.whatsapp", "WhatsApp"))
            lower in listOf("open phone", "open dialer", "launch phone", "phone app", "dialer") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("phone", "Phone"))
            lower in listOf("open messages", "open sms", "launch messages", "messages app", "text messages") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("messages", "Messages"))
            lower in listOf("open contacts", "launch contacts", "my contacts", "show contacts") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("contacts", "Contacts"))
            lower in listOf("open gallery", "open photos", "photos app", "view photos") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("gallery", "Gallery"))
            lower in listOf("open chrome", "launch chrome") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("chrome", "Chrome"))
            lower in listOf("open browser", "launch browser", "the browser", "my browser", "internet") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("chrome", "Browser"))
            lower in listOf("open youtube", "launch youtube", "watch videos") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("com.google.android.youtube", "YouTube"))
            lower in listOf("open maps", "open google maps", "navigation app") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("maps", "Maps"))
            lower in listOf("open calendar", "launch calendar", "my calendar") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("calendar", "Calendar"))
            lower in listOf("open clock", "launch clock") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("clock", "Clock"))
            lower in listOf("open calculator", "launch calculator") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("calculator", "Calculator"))
            lower in listOf("open files", "file manager", "my files", "open file manager") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("files", "Files"))
            lower in listOf("open play store", "google play", "app store", "open google play") ->
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp("play store", "Play Store"))
        }

        // Generic "open <app>" or "launch <app>"
        if ((lower.startsWith("open ") || lower.startsWith("launch ")) &&
            !lower.startsWith("open http://") && !lower.startsWith("open https://") && !lower.startsWith("open www.")
        ) {
            val appTarget = if (lower.startsWith("open ")) cleaned.substring(5).trim() else cleaned.substring(7).trim()
            if (appTarget.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(LocalCommand.LaunchApp(appTarget))
            }
        }

        return null
    }

    private fun routeCommunication(cleaned: String, lower: String): CommandRoutingResult? {
        // Direct call to number
        val callNumberMatch = Regex("""^(?:call|dial)\s+([+\d\s\-\(\)]{4,20})$""").find(lower)
        if (callNumberMatch != null) {
            val number = callNumberMatch.groupValues[1].replace(Regex("""[^\d+]"""), "")
            return CommandRoutingResult.LocalExecution(
                command = LocalCommand.PhoneCall(number = number),
                requiresConfirmation = true,
                confirmationPrompt = "Sir, shall I place a call to $number? Say yes to confirm or no to cancel.",
                sensitiveOperation = true
            )
        }

        // Call contact
        if (lower.startsWith("call ") || lower.startsWith("phone ")) {
            val contactName = if (lower.startsWith("call ")) cleaned.substring(5).trim() else cleaned.substring(6).trim()
            if (contactName.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(
                    command = LocalCommand.PhoneCall(contactName = contactName),
                    requiresConfirmation = true,
                    confirmationPrompt = "Sir, shall I call $contactName? Say yes to confirm or no to cancel.",
                    sensitiveOperation = true
                )
            }
        }

        // SMS to contact or number with message
        val smsMatch = Regex("""^(?:send\s+sms\s+to|send\s+message\s+to|text|message)\s+([a-zA-Z0-9\s]+?)\s+(?:saying|message|that)\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleaned)
        if (smsMatch != null) {
            val target = smsMatch.groupValues[1].trim()
            val message = smsMatch.groupValues[2].trim()
            val isNumber = target.all { it.isDigit() || it in "+- ()" }
            return CommandRoutingResult.LocalExecution(
                command = if (isNumber) LocalCommand.SmsMessage(number = target, message = message) else LocalCommand.SmsMessage(contactName = target, message = message),
                requiresConfirmation = true,
                confirmationPrompt = "Sir, shall I send \"$message\" to $target? Say yes to confirm or no to cancel.",
                sensitiveOperation = true
            )
        }

        // WhatsApp to contact with message
        val waMatch = Regex("""^(?:send\s+whatsapp\s+to|whatsapp)\s+([a-zA-Z0-9\s]+?)\s+(?:saying|message|that)\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleaned)
        if (waMatch != null) {
            val target = waMatch.groupValues[1].trim()
            val message = waMatch.groupValues[2].trim()
            return CommandRoutingResult.LocalExecution(
                command = LocalCommand.WhatsAppMessage(contactName = target, message = message),
                requiresConfirmation = true,
                confirmationPrompt = "Sir, shall I send \"$message\" to $target on WhatsApp? Say yes to confirm or no to cancel.",
                sensitiveOperation = true
            )
        }

        // Email
        val emailMatch = Regex("""^(?:send\s+email\s+to|email)\s+([^\s]+@[^\s]+|[a-zA-Z0-9\s]+?)(?:\s+subject\s+(.+?))?(?:\s+body\s+(.+))?$""", RegexOption.IGNORE_CASE).find(cleaned)
        if (emailMatch != null && (lower.startsWith("send email to") || lower.startsWith("email "))) {
            val recipient = emailMatch.groupValues[1].trim()
            val subject = emailMatch.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            val body = emailMatch.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
            return CommandRoutingResult.LocalExecution(
                command = LocalCommand.SendEmail(recipient = recipient, subject = subject, body = body),
                requiresConfirmation = true,
                confirmationPrompt = "Sir, shall I draft an email to $recipient${if (subject != null) " with subject \"$subject\"" else ""}? Say yes to confirm or no to cancel.",
                sensitiveOperation = true
            )
        }

        return null
    }

    private fun routeAlarmsAndTimers(cleaned: String, lower: String): CommandRoutingResult? {
        // Alarm
        if (lower.startsWith("set alarm") || lower.startsWith("alarm for") || lower.startsWith("wake me up at")) {
            val parsedTime = co.aura.actions.parseTime(lower)
            if (parsedTime != null) {
                return CommandRoutingResult.LocalExecution(
                    LocalCommand.AlarmControl(hour = parsedTime.first, minute = parsedTime.second)
                )
            }
        }

        // Timer
        if (lower.startsWith("set timer") || lower.startsWith("timer for") || lower.startsWith("set a timer")) {
            val sec = parseTimerDurationSeconds(lower)
            if (sec != null && sec > 0) {
                return CommandRoutingResult.LocalExecution(
                    LocalCommand.TimerControl(durationSeconds = sec)
                )
            }
        }

        return null
    }

    private fun routeNotesAndReminders(cleaned: String, lower: String): CommandRoutingResult? {
        // Read notes
        if (lower in listOf("read my notes", "read notes", "show my notes", "list notes", "what are my notes")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.NoteManage(NoteOperationType.READ_ALL, ""))
        }

        // Search notes
        if (lower.startsWith("search notes for ") || lower.startsWith("find note about ")) {
            val query = if (lower.startsWith("search notes for ")) cleaned.substring(17).trim() else cleaned.substring(16).trim()
            return CommandRoutingResult.LocalExecution(LocalCommand.NoteManage(NoteOperationType.SEARCH, query))
        }

        // Delete note
        if (lower.startsWith("delete note ") || lower.startsWith("remove note ")) {
            val query = if (lower.startsWith("delete note ")) cleaned.substring(12).trim() else cleaned.substring(12).trim()
            return CommandRoutingResult.LocalExecution(
                command = LocalCommand.NoteManage(NoteOperationType.DELETE, query),
                requiresConfirmation = true,
                confirmationPrompt = "Sir, shall I delete the note \"$query\"? Say yes to confirm or no to cancel.",
                sensitiveOperation = true
            )
        }

        // Create note
        val notePrefixes = listOf("take a note ", "create a note ", "new note ", "note that ", "add a note ")
        for (prefix in notePrefixes) {
            if (lower.startsWith(prefix)) {
                val content = cleaned.substring(prefix.length).trim()
                if (content.isNotBlank()) {
                    return CommandRoutingResult.LocalExecution(LocalCommand.NoteManage(NoteOperationType.CREATE, content))
                }
            }
        }

        // Reminder
        if (lower in listOf("show my reminders", "list reminders", "read reminders", "read my reminders", "what are my reminders", "show reminders")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.ReminderManage(ReminderOperationType.LIST, ""))
        }
        if (lower.startsWith("remind me to ") || lower.startsWith("create reminder ")) {
            val title = if (lower.startsWith("remind me to ")) cleaned.substring(13).trim() else cleaned.substring(16).trim()
            if (title.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(LocalCommand.ReminderManage(ReminderOperationType.CREATE, title))
            }
        }

        return null
    }

    private fun routeMemory(cleaned: String, lower: String): CommandRoutingResult? {
        if (lower.startsWith("remember that ") || lower.startsWith("remember ")) {
            val content = if (lower.startsWith("remember that ")) cleaned.substring(14).trim() else cleaned.substring(9).trim()
            if (content.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(LocalCommand.MemoryManage(isRemember = true, isForget = false, isQuery = false, content = content))
            }
        }
        if (lower.startsWith("forget that ") || lower.startsWith("forget ")) {
            val content = if (lower.startsWith("forget that ")) cleaned.substring(12).trim() else cleaned.substring(7).trim()
            if (content.isNotBlank()) {
                return CommandRoutingResult.LocalExecution(
                    command = LocalCommand.MemoryManage(isRemember = false, isForget = true, isQuery = false, content = content),
                    requiresConfirmation = true,
                    confirmationPrompt = "Sir, shall I forget that preference: \"$content\"? Say yes to confirm or no to cancel.",
                    sensitiveOperation = true
                )
            }
        }
        if (lower in listOf("what do you remember about me", "list my memories", "show what you remember", "what is in your memory")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.MemoryManage(isRemember = false, isForget = false, isQuery = true, content = ""))
        }

        return null
    }

    private fun routeMedia(cleaned: String, lower: String): CommandRoutingResult? {
        if (lower in listOf("pause music", "pause playback", "pause media")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.MediaControl("PAUSE"))
        }
        if (lower in listOf("resume music", "resume playback", "resume media")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.MediaControl("RESUME"))
        }
        if (lower in listOf("stop music", "stop media", "stop playback")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.MediaControl("STOP"))
        }
        if (lower in listOf("next track", "next song", "skip song", "skip track")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.MediaControl("NEXT"))
        }
        if (lower in listOf("previous track", "previous song")) {
            return CommandRoutingResult.LocalExecution(LocalCommand.MediaControl("PREVIOUS"))
        }

        // "play <song> [on/from/in <app>]"
        val playMatch = Regex("""^play\s+(.+?)(?:(?:\s+on|\s+from|\s+in)\s+([a-zA-Z0-9\s]+))?$""", RegexOption.IGNORE_CASE).find(cleaned)
        if (playMatch != null && !lower.startsWith("play music")) {
            val songQuery = playMatch.groupValues[1].trim()
            val source = playMatch.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            return CommandRoutingResult.LocalExecution(LocalCommand.MediaControl("PLAY", query = songQuery, source = source))
        }

        return null
    }

    private fun parseTimerDurationSeconds(lower: String): Long? {
        var totalSec = 0L
        val minMatch = Regex("""(\d+)\s*(?:minute|min|minutes|mins)""").find(lower)
        if (minMatch != null) {
            totalSec += (minMatch.groupValues[1].toLongOrNull() ?: 0L) * 60L
        }
        val secMatch = Regex("""(\d+)\s*(?:second|sec|seconds|secs)""").find(lower)
        if (secMatch != null) {
            totalSec += secMatch.groupValues[1].toLongOrNull() ?: 0L
        }
        val hourMatch = Regex("""(\d+)\s*(?:hour|hours|hr|hrs)""").find(lower)
        if (hourMatch != null) {
            totalSec += (hourMatch.groupValues[1].toLongOrNull() ?: 0L) * 3600L
        }
        return if (totalSec > 0) totalSec else null
    }

    private fun classifyGeminiReason(lower: String): String {
        return when {
            lower.contains("who is better") || lower.contains("compare") || lower.contains("difference between") -> "COMPARISON"
            lower.contains("explain") || lower.contains("how does") || lower.contains("why does") || lower.contains("why is") -> "EXPLANATION"
            lower.contains("code") || lower.contains("debug") || lower.contains("python") || lower.contains("kotlin") || lower.contains("sql") -> "CODING"
            lower.contains("write") || lower.contains("poem") || lower.contains("essay") || lower.contains("idea") -> "CREATIVE_GENERATION"
            lower.contains("summarize") || lower.contains("summary") -> "SUMMARIZATION"
            else -> "GENERAL_INTELLIGENCE"
        }
    }
}
