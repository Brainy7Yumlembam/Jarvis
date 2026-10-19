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
    private val contactResolver: ContactResolver,
    private val noteManager: co.aura.router.LocalNoteManager? = null
) : ActionExecutor {

    private val appLauncher by lazy { AndroidAppLauncher(context, appRegistry, aliasResolver) }
    private val capabilityManager by lazy { DeviceCapabilityManager(context) }

    override suspend fun executeAction(action: Action): ActionResult {
        AuraLogger.i(LogCategory.ACTION, "Executing action: ${action.actionType}")
        return try {
            when (action) {
                is OpenAppAction       -> appLauncher.launchAppByNameOrCategory(action.appName)
                is OpenSettingsAction  -> appLauncher.openSettingsPanel(action.panel)
                is OpenUrlAction       -> appLauncher.openUrl(action.url)
                is WebSearchAction     -> appLauncher.webSearch(action.query)
                is SendEmailAction     -> appLauncher.sendEmail(action.recipient, action.subject, action.body)
                is ShareTextAction     -> appLauncher.shareText(action.text, action.title)
                is CopyToClipboardAction -> appLauncher.copyToClipboard(action.text, action.label)

                is GetDeviceInfoAction -> {
                    when (action.infoType) {
                        DeviceInfoType.DATE -> capabilityManager.getCurrentDate()
                        DeviceInfoType.TIME -> capabilityManager.getCurrentTime()
                        DeviceInfoType.BATTERY -> capabilityManager.getBatteryLevel()
                        DeviceInfoType.CHARGING -> capabilityManager.getChargingStatus()
                        DeviceInfoType.ANDROID_VERSION -> capabilityManager.getAndroidVersion()
                        DeviceInfoType.STORAGE -> capabilityManager.getStorageInfo()
                        DeviceInfoType.NETWORK_CONNECTIVITY -> capabilityManager.getConnectivityStatus()
                        DeviceInfoType.WIFI_STATUS -> capabilityManager.getWifiStatus()
                        DeviceInfoType.BLUETOOTH_STATUS -> capabilityManager.getBluetoothStatus()
                        DeviceInfoType.FLASHLIGHT_STATUS -> capabilityManager.getFlashlightStatus()
                    }
                }
                is GetBatteryAction    -> capabilityManager.getBatteryLevel()
                is GetCurrentTimeAction -> capabilityManager.getCurrentTime()
                is ToggleFlashlightAction -> capabilityManager.setFlashlight(action.enabled)
                is VolumeUpAction      -> capabilityManager.adjustVolumeStep(increase = true)
                is VolumeDownAction    -> capabilityManager.adjustVolumeStep(increase = false)
                is SetVolumeAction     -> capabilityManager.setVolumeLevel(action.level)
                is MuteVolumeAction    -> capabilityManager.muteVolume()
                is ShowVolumePanelAction -> capabilityManager.showVolumePanel()

                is ManageNoteAction    -> {
                    val manager = noteManager ?: co.aura.router.LocalNoteManager(co.aura.memory.MemoryManagerImpl(co.aura.data.repository.MemoryRepositoryImpl(co.aura.core.database.DatabaseHelper(app.cash.sqldelight.driver.android.AndroidSqliteDriver(co.aura.database.AuraDatabase.Schema, context, "aura.db"))), co.aura.data.repository.ConversationRepositoryImpl(co.aura.core.database.DatabaseHelper(app.cash.sqldelight.driver.android.AndroidSqliteDriver(co.aura.database.AuraDatabase.Schema, context, "aura.db"))), co.aura.memory.KeywordMemoryRetriever(co.aura.data.repository.MemoryRepositoryImpl(co.aura.core.database.DatabaseHelper(app.cash.sqldelight.driver.android.AndroidSqliteDriver(co.aura.database.AuraDatabase.Schema, context, "aura.db"))))))
                    when (action.operation) {
                        NoteOperationType.CREATE -> ActionResult.Success(manager.addNote(action.content))
                        NoteOperationType.READ_ALL -> ActionResult.Success(manager.readNotesSummary())
                        NoteOperationType.SEARCH -> ActionResult.Success(manager.searchNotes(action.content))
                        NoteOperationType.DELETE -> ActionResult.Success(manager.deleteNote(action.content))
                    }
                }
                is ManageReminderAction -> {
                    val manager = noteManager ?: co.aura.router.LocalNoteManager(co.aura.memory.MemoryManagerImpl(co.aura.data.repository.MemoryRepositoryImpl(co.aura.core.database.DatabaseHelper(app.cash.sqldelight.driver.android.AndroidSqliteDriver(co.aura.database.AuraDatabase.Schema, context, "aura.db"))), co.aura.data.repository.ConversationRepositoryImpl(co.aura.core.database.DatabaseHelper(app.cash.sqldelight.driver.android.AndroidSqliteDriver(co.aura.database.AuraDatabase.Schema, context, "aura.db"))), co.aura.memory.KeywordMemoryRetriever(co.aura.data.repository.MemoryRepositoryImpl(co.aura.core.database.DatabaseHelper(app.cash.sqldelight.driver.android.AndroidSqliteDriver(co.aura.database.AuraDatabase.Schema, context, "aura.db"))))))
                    when (action.operation) {
                        ReminderOperationType.CREATE -> ActionResult.Success(manager.addReminder(action.title))
                        ReminderOperationType.LIST -> ActionResult.Success(manager.listReminders())
                        ReminderOperationType.CANCEL -> ActionResult.Success("Reminder cancelled, sir.")
                    }
                }

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
                is TakeScreenshotAction -> handleScreenshot()
                is CameraCaptureAction -> handleCameraCapture(action.autoCapture)
                else -> ActionResult.Failure("Sir, that action type isn't supported yet.")
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Error executing action ${action.actionType}", e)
            ActionResult.Failure("Sir, an error occurred while executing the request.")
        }
    }

    // ─── App Launching ──────────────────────────────────────────────────────

    private fun handleOpenApp(action: OpenAppAction): ActionResult {
        val appName = action.appName.trim()
        if (appName.isBlank()) return ActionResult.Failure("Sir, the application name is empty.")
        val installedApps = appRegistry.getInstalledApps()
        val normalizedQuery = aliasResolver.normalize(appName)
        val canonicalKey = aliasResolver.getCanonicalKey(appName) ?: normalizedQuery

        // 1. Check saved preferred package
        val savedPackage = runBlocking {
            securityManager.getSecureToken("preferred_app_$canonicalKey")
                ?: securityManager.getSecureToken("preferred_app_$normalizedQuery")
        }
        if (!savedPackage.isNullOrBlank()) {
            val matchingApp = installedApps.firstOrNull { it.packageName == savedPackage }
            val label = matchingApp?.label ?: appName
            val launchResult = launchApp(savedPackage, label)
            if (launchResult is ActionResult.Success) {
                return launchResult
            }
        }

        // 2. Resolve via AppAliasResolver
        val result = aliasResolver.resolve(appName, installedApps)
        when (result.confidence) {
            MatchConfidence.EXACT, MatchConfidence.ALIAS, MatchConfidence.STRONG, MatchConfidence.FUZZY -> {
                val matched = result.matchedApp!!
                val launchResult = launchApp(matched.packageName, matched.label)
                if (launchResult is ActionResult.Success) {
                    return launchResult
                }
            }
            MatchConfidence.AMBIGUOUS -> {
                val categoryName = when (canonicalKey) {
                    "chrome" -> "browser"; "music" -> "music app"; "gallery" -> "gallery"; else -> canonicalKey
                }
                return ActionResult.Ambiguity(
                    message = "Sir, I found several candidates for $categoryName: ${result.candidates.joinToString(", ") { it.label }}. Which one?",
                    candidates = result.candidates
                )
            }
            MatchConfidence.NONE -> {
                // Continue to system intent fallback
            }
        }

        // 3. Category / System Intent Fallback for standard Android system capabilities (Camera, Settings, etc.)
        val systemResult = trySystemIntentForApp(canonicalKey, appName)
        if (systemResult != null) {
            return systemResult
        }

        // 4. App not found or failed to launch
        return ActionResult.Failure("Sorry, Sir. I couldn't find $appName on your device.")
    }

    private fun trySystemIntentForApp(canonicalKey: String, requestedName: String): ActionResult? {
        val pm = context.packageManager
        val standardIntent = when (canonicalKey) {
            "camera" -> Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            "chrome" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_BROWSER) }
            "gallery" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_GALLERY) }
            "music" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MUSIC) }
            "phone" -> Intent(Intent.ACTION_DIAL)
            "messages" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) }
            "calculator" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALCULATOR) }
            "maps" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MAPS) }
            "gmail" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_EMAIL) }
            else -> null
        }

        if (standardIntent != null) {
            standardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolveInfo = pm.resolveActivity(standardIntent, 0)
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                val label = resolveInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: requestedName
                return launchIntent(standardIntent, label)
            }
        }

        // Secondary fallback for camera
        if (canonicalKey == "camera") {
            val captureIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val captureInfo = pm.resolveActivity(captureIntent, 0)
            if (captureInfo != null && captureInfo.activityInfo != null) {
                val label = captureInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: requestedName
                return launchIntent(captureIntent, label)
            }
        }

        return null
    }

    private fun launchApp(packageName: String, appLabel: String? = null): ActionResult {
        val pm = context.packageManager
        val name = appLabel ?: packageName
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failure("Sorry, Sir. I couldn't open $name.")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchIntent(launchIntent, name)
    }

    private fun launchIntent(intent: Intent, appLabel: String): ActionResult {
        val pm = context.packageManager
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolveInfo = pm.resolveActivity(intent, 0)
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            AuraLogger.w(LogCategory.ACTION, "Intent failed to resolve to an activity for $appLabel: $intent")
            return ActionResult.Failure("Sorry, Sir. I couldn't open $appLabel.")
        }

        return try {
            context.startActivity(intent)
            AuraLogger.i(LogCategory.ACTION, "Successfully initiated launch for $appLabel")
            ActionResult.Success("Opening $appLabel, Sir.")
        } catch (e: android.content.ActivityNotFoundException) {
            AuraLogger.e(LogCategory.ACTION, "ActivityNotFoundException when launching $appLabel", e)
            ActionResult.Failure("Sorry, Sir. I couldn't open $appLabel.")
        } catch (e: SecurityException) {
            AuraLogger.e(LogCategory.ACTION, "SecurityException when launching $appLabel", e)
            ActionResult.Failure("Sir, permission was denied to open $appLabel.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to launch $appLabel", e)
            ActionResult.Failure("Sorry, Sir. I couldn't open $appLabel.")
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
            val pm = context.packageManager
            val hasCallPermission = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasCallPermission) {
                AuraLogger.i(LogCategory.ACTION, "CALL_PHONE permission not granted, opening dialer")
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = android.net.Uri.parse("tel:${contact.phoneNumber}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(pm) == null) {
                    return ActionResult.Failure("Sorry, Sir. No phone dialer is available on this device.")
                }
                context.startActivity(intent)
                return ActionResult.Success("Opening dialer for ${contact.name}, Sir.")
            }
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:${contact.phoneNumber}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) == null) {
                return ActionResult.Failure("Sorry, Sir. I couldn't place the call to ${contact.name}.")
            }
            context.startActivity(intent)
            ActionResult.Success("Calling ${contact.name}, Sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to place call to ${contact.name}", e)
            ActionResult.Failure("Sorry, Sir. I couldn't place the call to ${contact.name}.")
        }
    }

    private fun handleCallByNumber(phoneNumber: String): ActionResult {
        if (phoneNumber.isBlank()) return ActionResult.Failure("Sir, the phone number is blank.")
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) == null) {
                return ActionResult.Failure("Sorry, Sir. No phone dialer is available on this device.")
            }
            context.startActivity(intent)
            ActionResult.Success("Opening dialer, Sir.")
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
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:${contact.phoneNumber}")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) == null) {
                return ActionResult.Failure("Sorry, Sir. No SMS application is available on this device.")
            }
            context.startActivity(intent)
            ActionResult.Success("Opening message composer for ${contact.name}, Sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to open SMS composer for ${contact.name}", e)
            ActionResult.Failure("Sir, I couldn't open the message composer.")
        }
    }

    private fun handleSmsToNumber(phoneNumber: String, message: String): ActionResult {
        if (phoneNumber.isBlank()) return ActionResult.Failure("Sir, the phone number is blank.")
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) == null) {
                return ActionResult.Failure("Sorry, Sir. No SMS application is available on this device.")
            }
            context.startActivity(intent)
            ActionResult.Success("Opening SMS composer, Sir.")
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
                        ActionResult.Success("Opening WhatsApp conversation with ${contact.name}, Sir. The message is pre-filled.")
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
            val pm = context.packageManager
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) == null) {
                return ActionResult.Failure("Sorry, Sir. No alarm clock application is available.")
            }
            context.startActivity(intent)
            val displayTime = String.format("%02d:%02d", hour, minute)
            ActionResult.Success("Alarm set for $displayTime, Sir.")
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
            val pm = context.packageManager
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds.toInt())
                if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) == null) {
                return ActionResult.Failure("Sorry, Sir. No timer application is available.")
            }
            context.startActivity(intent)
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            val display = if (minutes > 0 && seconds == 0L) "$minutes minute${if (minutes > 1) "s" else ""}"
                else if (minutes > 0) "$minutes min $seconds sec"
                else "$seconds seconds"
            ActionResult.Success("Timer set for $display, Sir.")
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

    private fun handleCameraCapture(autoCapture: Boolean): ActionResult {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AuraLogger.w(LogCategory.ACTION, "[CAMERA] Camera permission is not granted")
            return ActionResult.Failure("Camera permission is required to capture photos, sir. Please grant it in application settings.")
        }

        val intent = Intent("co.aura.action.CAMERA_CAPTURE").apply {
            setPackage(context.packageName)
            putExtra("EXTRA_AUTO_CAPTURE", autoCapture)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            intent.setClassName(context.packageName, "co.aura.android.JarvisCameraActivity")
        }

        AuraLogger.i(LogCategory.ACTION, "[CAMERA] Initiating in-app camera capture (autoCapture=$autoCapture)")
        return appLauncher.launchExplicitIntent(intent, "Camera Shutter")
    }
}
