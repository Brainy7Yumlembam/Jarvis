package co.aura.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import co.aura.communication.ContactInfo
import co.aura.communication.ContactResolver
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidActionExecutor(
    private val context: Context,
    private val appRegistry: InstalledAppRegistry,
    private val aliasResolver: AppAliasResolver,
    private val securityManager: SecurityManager,
    private val contactResolver: ContactResolver
) : ActionExecutor {

    override suspend fun executeAction(action: Action): ActionResult {
        AuraLogger.i(LogCategory.ACTION, "Executing action: ${action.actionType}")
        return try {
            when (action) {
                is OpenAppAction       -> handleOpenApp(action)
                is AlarmAction         -> handleSetAlarmByString(action.time)
                is SetAlarmAction      -> handleSetAlarm(action.hour, action.minute, action.label)
                is SetTimerAction      -> handleSetTimer(action.durationSeconds, action.label)
                is CallAction          -> handleCallByNumber(action.phoneNumber)
                is CallContactAction   -> handleCallContact(action.contactName)
                is SmsAction           -> handleSmsToNumber(action.phoneNumber, action.message)
                is SendSmsAction       -> handleSmsToContact(action.contactName, action.message)
                is SendWhatsAppAction  -> handleWhatsApp(action.contactName, action.message)
                is PlayMediaAction     -> handlePlayMedia(action.query, action.targetPackage)
                is PauseMediaAction    -> handleMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
                is ResumeMediaAction   -> handleMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Resumed")
                is SkipMediaAction     -> handleMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipped to next track")
                is PreviousMediaAction -> handleMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Playing previous track")
                is StopMediaAction     -> handleMediaKey(KeyEvent.KEYCODE_MEDIA_STOP, "Stopped playback")
                is VolumeUpAction      -> handleVolumeStep(increase = true)
                is VolumeDownAction    -> handleVolumeStep(increase = false)
                is SetVolumeAction     -> handleSetVolume(action.level)
                is ToggleFlashlightAction -> handleFlashlight(action.enabled)
                is TakeScreenshotAction -> handleScreenshot()
                is GetBatteryAction    -> handleGetBattery()
                is GetCurrentTimeAction -> handleGetCurrentTime()
                else -> ActionResult.Failure("Sir, that action type isn't supported yet.")
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Error executing action ${action.actionType}", e)
            ActionResult.Failure("Sir, an error occurred while executing the request.")
        }
    }

    // ─── App Launching ──────────────────────────────────────────────────────

    private fun handleOpenApp(action: OpenAppAction): ActionResult {
        val appName = action.appName
        if (appName.isBlank()) return ActionResult.Failure("Sir, the application name is empty.")
        val installedApps = appRegistry.getInstalledApps()
        val normalizedQuery = aliasResolver.normalize(appName)
        val canonicalKey = aliasResolver.getCanonicalKey(appName) ?: normalizedQuery
        val savedPackage = runBlocking {
            securityManager.getSecureToken("preferred_app_$canonicalKey")
                ?: securityManager.getSecureToken("preferred_app_$normalizedQuery")
        }
        if (savedPackage != null) {
            val matchingApp = installedApps.firstOrNull { it.packageName == savedPackage }
            if (matchingApp != null) return launchApp(matchingApp.packageName)
        }
        val result = aliasResolver.resolve(appName, installedApps)
        return when (result.confidence) {
            MatchConfidence.EXACT, MatchConfidence.ALIAS, MatchConfidence.STRONG, MatchConfidence.FUZZY ->
                launchApp(result.matchedApp!!.packageName)
            MatchConfidence.AMBIGUOUS -> {
                val categoryName = when (canonicalKey) {
                    "chrome" -> "browser"; "music" -> "music app"; "gallery" -> "gallery"; else -> canonicalKey
                }
                ActionResult.Ambiguity(
                    message = "Sir, I found several candidates for $categoryName: ${result.candidates.joinToString(", ") { it.label }}. Which one?",
                    candidates = result.candidates
                )
            }
            MatchConfidence.NONE -> ActionResult.Failure("Sorry, sir. I couldn't find that application on your device.")
        }
    }

    private fun launchApp(packageName: String): ActionResult {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ActionResult.Failure("Sir, $packageName doesn't appear to be launchable on this device.")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ActionResult.Success("Opening application, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to launch $packageName", e)
            ActionResult.Failure("Sir, I was unable to open the application.")
        }
    }

    // ─── Media Controls ──────────────────────────────────────────────────────

    private fun handlePlayMedia(query: String, targetPackage: String? = null): ActionResult {
        return try {
            val pm = context.packageManager
            AuraLogger.d(LogCategory.ACTION, "Executing PlayMedia query='$query', targetPackage='$targetPackage'")

            if (!targetPackage.isNullOrBlank()) {
                val targetApp = appRegistry.getInstalledApps().firstOrNull { it.packageName == targetPackage }
                val targetLabel = targetApp?.label ?: targetPackage
                AuraLogger.d(LogCategory.ACTION, "Resolved target music app label: '$targetLabel', package: '$targetPackage'")

                // 1. Try target app specific MediaStore search intent
                val mediaSearchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(targetPackage)
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, query)
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (mediaSearchIntent.resolveActivity(pm) != null) {
                    context.startActivity(mediaSearchIntent)
                    return ActionResult.Success("Playing $query on $targetLabel, sir.")
                }

                // 2. Fallback: Launch app if track playback cannot be initiated directly
                val launchResult = launchApp(targetPackage)
                return if (launchResult is ActionResult.Success) {
                    // DO NOT claim "Playing $query" when only app launch succeeded
                    ActionResult.Success("I opened $targetLabel, sir, but it doesn't support direct track playback.")
                } else {
                    ActionResult.Failure("Sir, I couldn't start media playback on $targetLabel.")
                }
            }

            // General capability-based fallback
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, query)
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
                ActionResult.Success("Playing $query, sir.")
            } else {
                val musicApps = appRegistry.getInstalledApps().filter { app ->
                    AppCapability.MEDIA_PLAY in app.capabilities || AppCapability.MEDIA_SEARCH in app.capabilities
                }
                if (musicApps.isNotEmpty()) {
                    val app = musicApps.first()
                    launchApp(app.packageName)
                    ActionResult.Success("I opened ${app.label}, sir, but it doesn't support direct track playback.")
                } else {
                    ActionResult.Failure("Sir, I couldn't find a media player to play $query.")
                }
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to play media", e)
            ActionResult.Failure("Sir, I couldn't start media playback.")
        }
    }

    private fun handleMediaKey(keyCode: Int, successMessage: String): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            ActionResult.Success("$successMessage, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to dispatch media key $keyCode", e)
            ActionResult.Failure("Sir, no active media session is responding.")
        }
    }

    // ─── Calls ───────────────────────────────────────────────────────────────

    private suspend fun handleCallContact(contactName: String): ActionResult {
        val contacts = contactResolver.resolveContact(contactName)
        return when {
            contacts.isEmpty() ->
                ActionResult.Failure("Sir, I couldn't find a contact named $contactName.")
            contacts.size > 1 -> {
                val names = contacts.joinToString(", ") { it.name }
                ActionResult.Ambiguity(
                    message = "Sir, I found multiple contacts matching $contactName: $names. Which one should I call?",
                    candidates = emptyList()
                )
            }
            else -> {
                val contact = contacts.first()
                placeCall(contact)
            }
        }
    }

    private fun placeCall(contact: ContactInfo): ActionResult {
        return try {
            val hasCallPermission = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasCallPermission) {
                // Fallback: open dialer without auto-dial (no phone number logged)
                AuraLogger.i(LogCategory.ACTION, "CALL_PHONE permission not granted, opening dialer")
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = android.net.Uri.parse("tel:${contact.phoneNumber}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return ActionResult.Success("Opening dialer for ${contact.name}, sir.")
            }
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:${contact.phoneNumber}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // NOTE: phone number is NOT logged — only contact name
            ActionResult.Success("Calling ${contact.name}, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to place call to ${contact.name}", e)
            ActionResult.Failure("Sir, I couldn't place the call.")
        }
    }

    private fun handleCallByNumber(phoneNumber: String): ActionResult {
        if (phoneNumber.isBlank()) return ActionResult.Failure("Sir, the phone number is blank.")
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Opening dialer, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to open dialer", e)
            ActionResult.Failure("Sir, I could not open the phone dialer.")
        }
    }

    // ─── SMS ─────────────────────────────────────────────────────────────────

    private suspend fun handleSmsToContact(contactName: String, message: String): ActionResult {
        val contacts = contactResolver.resolveContact(contactName)
        return when {
            contacts.isEmpty() ->
                ActionResult.Failure("Sir, I couldn't find a contact named $contactName.")
            contacts.size > 1 -> {
                val names = contacts.joinToString(", ") { it.name }
                ActionResult.Ambiguity(
                    message = "Sir, I found multiple contacts matching $contactName: $names. Which one should I message?",
                    candidates = emptyList()
                )
            }
            else -> {
                val contact = contacts.first()
                openSmsComposer(contact, message)
            }
        }
    }

    private fun openSmsComposer(contact: ContactInfo, message: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:${contact.phoneNumber}")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // NOTE: phone number not logged — only contact name
            ActionResult.Success("Opening message composer for ${contact.name}, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to open SMS composer for ${contact.name}", e)
            ActionResult.Failure("Sir, I couldn't open the message composer.")
        }
    }

    private fun handleSmsToNumber(phoneNumber: String, message: String): ActionResult {
        if (phoneNumber.isBlank()) return ActionResult.Failure("Sir, the phone number is blank.")
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Opening SMS composer, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to open SMS composer", e)
            ActionResult.Failure("Sir, I couldn't open the SMS composer.")
        }
    }

    // ─── WhatsApp ────────────────────────────────────────────────────────────

    private suspend fun handleWhatsApp(contactName: String, message: String): ActionResult {
        // Resolve contact locally — phone number never goes to Gemini
        val contacts = contactResolver.resolveContact(contactName)
        return when {
            contacts.isEmpty() ->
                ActionResult.Failure("Sir, I couldn't find a contact named $contactName for WhatsApp.")
            contacts.size > 1 -> {
                val names = contacts.joinToString(", ") { it.name }
                ActionResult.Ambiguity(
                    message = "Sir, I found multiple contacts for $contactName: $names. Which one should I message on WhatsApp?",
                    candidates = emptyList()
                )
            }
            else -> {
                val contact = contacts.first()
                // Normalize phone number: strip non-digits, ensure country code
                val rawNumber = contact.phoneNumber.replace(Regex("[^\\d+]"), "")
                try {
                    val whatsappIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$rawNumber&text=${android.net.Uri.encode(message)}")
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (whatsappIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(whatsappIntent)
                        // NOTE: phone number NOT in success message — only contact name
                        ActionResult.Success("Opening WhatsApp conversation with ${contact.name}, sir. The message is pre-filled.")
                    } else {
                        ActionResult.Failure("Sir, WhatsApp doesn't appear to be installed on this device.")
                    }
                } catch (e: Exception) {
                    AuraLogger.e(LogCategory.ACTION, "Failed to open WhatsApp for ${contact.name}", e)
                    ActionResult.Failure("Sir, I couldn't open WhatsApp.")
                }
            }
        }
    }

    // ─── Alarm & Timer ───────────────────────────────────────────────────────

    private fun handleSetAlarm(hour: Int, minute: Int, label: String?): ActionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionResult.Failure("Sir, $hour:$minute is not a valid time.")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val displayTime = String.format("%02d:%02d", hour, minute)
            ActionResult.Success("Alarm set for $displayTime, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to set alarm", e)
            ActionResult.Failure("Sir, I was unable to schedule the alarm.")
        }
    }

    private fun handleSetAlarmByString(timeStr: String): ActionResult {
        val parsed = co.aura.actions.parseTime(timeStr)
            ?: return ActionResult.Failure("Sir, I could not parse '$timeStr' as a valid time.")
        return handleSetAlarm(parsed.first, parsed.second, null)
    }

    private fun handleSetTimer(durationSeconds: Long, label: String?): ActionResult {
        if (durationSeconds <= 0) return ActionResult.Failure("Sir, the timer duration must be greater than zero.")
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds.toInt())
                if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            val display = if (minutes > 0 && seconds == 0L) "$minutes minute${if (minutes > 1) "s" else ""}"
                else if (minutes > 0) "$minutes min $seconds sec"
                else "$seconds seconds"
            ActionResult.Success("Timer set for $display, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to set timer", e)
            ActionResult.Failure("Sir, I was unable to set the timer.")
        }
    }

    // ─── Volume ───────────────────────────────────────────────────────────────

    private fun handleVolumeStep(increase: Boolean): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            if (increase) ActionResult.Success("Volume increased, sir.")
            else ActionResult.Success("Volume decreased, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to adjust volume", e)
            ActionResult.Failure("Sir, I couldn't adjust the volume.")
        }
    }

    private fun handleSetVolume(level: Int): ActionResult {
        val clamped = level.coerceIn(0, 100)
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (clamped * maxVolume / 100.0).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            ActionResult.Success("Volume set to $clamped percent, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to set volume", e)
            ActionResult.Failure("Sir, I couldn't set the volume.")
        }
    }

    // ─── Flashlight ──────────────────────────────────────────────────────────

    private fun handleFlashlight(enabled: Boolean): ActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ActionResult.Failure("Sir, this device doesn't appear to have a flashlight.")
            cameraManager.setTorchMode(cameraId, enabled)
            if (enabled) ActionResult.Success("Flashlight on, sir.")
            else ActionResult.Success("Flashlight off, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to toggle flashlight", e)
            ActionResult.Failure("Sir, I couldn't toggle the flashlight.")
        }
    }

    // ─── Screenshot ──────────────────────────────────────────────────────────

    private fun handleScreenshot(): ActionResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // On Android 9+ we can instruct via global actions key shortcut
                // NOTE: silent screenshots from background are not permitted by Android.
                // We signal user to use the hardware button instead.
                ActionResult.Failure("Sir, Android doesn't allow silent screenshots from a background service. Please use the hardware buttons (Power + Volume Down) to capture the screen.")
            } else {
                ActionResult.Failure("Sir, screenshot capture isn't supported on this Android version via voice.")
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to take screenshot", e)
            ActionResult.Failure("Sir, screenshot capture failed.")
        }
    }

    // ─── Battery & Time ──────────────────────────────────────────────────────

    private fun handleGetBattery(): ActionResult {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1
        return if (batteryPct >= 0) ActionResult.Success("Your battery is at $batteryPct percent, sir.")
        else ActionResult.Failure("Sir, I was unable to read the battery level.")
    }

    private fun handleGetCurrentTime(): ActionResult {
        val formattedTime = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        return ActionResult.Success("It is $formattedTime, sir.")
    }
}
