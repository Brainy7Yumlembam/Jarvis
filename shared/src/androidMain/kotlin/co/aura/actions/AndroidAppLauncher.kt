package co.aura.actions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

/**
 * Android application launcher and system intent resolver.
 * Handles launch of standard and third-party applications, settings panels,
 * deep links, sharesheet, and clipboard.
 */
class AndroidAppLauncher(
    private val context: Context,
    private val appRegistry: InstalledAppRegistry,
    private val aliasResolver: AppAliasResolver
) {

    private val pm = context.packageManager

    fun launchAppByNameOrCategory(requestedName: String): ActionResult {
        val appName = requestedName.trim()
        if (appName.isBlank()) return ActionResult.Failure("Sir, the application name was not specified.")

        val installedApps = appRegistry.getInstalledApps()
        val normalizedQuery = aliasResolver.normalize(appName)
        val canonicalKey = aliasResolver.getCanonicalKey(appName) ?: normalizedQuery

        // 1. Resolve via AppAliasResolver
        val result = aliasResolver.resolve(appName, installedApps)
        when (result.confidence) {
            MatchConfidence.EXACT, MatchConfidence.ALIAS, MatchConfidence.STRONG, MatchConfidence.FUZZY -> {
                val matched = result.matchedApp!!
                val launchResult = launchPackage(matched.packageName, matched.label)
                if (launchResult is ActionResult.Success) {
                    return launchResult
                }
            }
            MatchConfidence.AMBIGUOUS -> {
                val categoryName = when (canonicalKey) {
                    "chrome" -> "browser"
                    "music" -> "music app"
                    "gallery" -> "gallery"
                    else -> canonicalKey
                }
                return ActionResult.Ambiguity(
                    message = "Sir, I found several candidates for $categoryName: ${result.candidates.joinToString(", ") { it.label }}. Which one would you prefer?",
                    candidates = result.candidates
                )
            }
            MatchConfidence.NONE -> {
                // Continue to system intent fallback
            }
        }

        // 2. Specific System Intent Fallback for core categories
        val systemResult = trySystemIntentForApp(canonicalKey, appName)
        if (systemResult != null) {
            return systemResult
        }

        return ActionResult.Failure("Sorry, sir. I couldn't find $appName installed on your device.")
    }

    fun launchPackage(packageName: String, label: String? = null): ActionResult {
        val name = label ?: packageName
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failure("Sorry, sir. I couldn't find $name on your device.")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return executeIntent(launchIntent, name)
    }

    fun launchExplicitIntent(intent: Intent, targetName: String): ActionResult {
        return executeIntent(intent, targetName)
    }

    fun openSettingsPanel(panel: SettingsPanel): ActionResult {
        val intent = when (panel) {
            SettingsPanel.MAIN -> Intent(Settings.ACTION_SETTINGS)
            SettingsPanel.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SettingsPanel.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsPanel.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SettingsPanel.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsPanel.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsPanel.BATTERY -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            SettingsPanel.HOTSPOT -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            SettingsPanel.NETWORK -> Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
            SettingsPanel.APPS -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
        }
        val panelName = panel.name.lowercase().replaceFirstChar { it.uppercase() }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return executeIntent(intent, "$panelName Settings")
    }

    fun openUrl(url: String): ActionResult {
        return try {
            val parsedUri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            executeIntent(intent, "Link")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to open URL $url", e)
            ActionResult.Failure("Sir, I was unable to open the specified URL.")
        }
    }

    fun webSearch(query: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) != null) {
                executeIntent(intent, "Search")
            } else {
                openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to perform web search for $query", e)
            ActionResult.Failure("Sir, I was unable to perform the search.")
        }
    }

    fun shareText(text: String, title: String? = null): ActionResult {
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, title ?: "Share via JARVIS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(shareIntent)
            ActionResult.Success("Sharing, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to share text", e)
            ActionResult.Failure("Sir, I was unable to open the share sheet.")
        }
    }

    fun copyToClipboard(text: String, label: String = "JARVIS"): ActionResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            ActionResult.Success("Copied to clipboard, sir.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to copy to clipboard", e)
            ActionResult.Failure("Sir, I couldn't copy to the clipboard.")
        }
    }

    fun sendEmail(recipient: String?, subject: String?, body: String?): ActionResult {
        return try {
            val mailtoUri = buildString {
                append("mailto:")
                if (!recipient.isNullOrBlank()) append(Uri.encode(recipient))
                val params = mutableListOf<String>()
                if (!subject.isNullOrBlank()) params.add("subject=${Uri.encode(subject)}")
                if (!body.isNullOrBlank()) params.add("body=${Uri.encode(body)}")
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
            val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (emailIntent.resolveActivity(pm) != null) {
                executeIntent(emailIntent, "Email Composer")
            } else {
                ActionResult.Failure("Sir, no email application is available on this device.")
            }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to prepare email", e)
            ActionResult.Failure("Sir, I was unable to open the email application.")
        }
    }

    private fun trySystemIntentForApp(canonicalKey: String, requestedName: String): ActionResult? {
        val standardIntent = when (canonicalKey) {
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "chrome" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_BROWSER) }
            "gallery" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_GALLERY) }
            "music" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MUSIC) }
            "phone" -> Intent(Intent.ACTION_DIAL)
            "messages" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) }
            "calculator" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALCULATOR) }
            "maps" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MAPS) }
            "gmail" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_EMAIL) }
            "calendar" -> Intent(Intent.ACTION_VIEW).apply {
                data = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
            }
            "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "contacts" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            "whatsapp" -> pm.getLaunchIntentForPackage("com.whatsapp")
            "youtube" -> pm.getLaunchIntentForPackage("com.google.android.youtube")
            "play store" -> pm.getLaunchIntentForPackage("com.android.vending")
            "files" -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            else -> null
        }

        if (standardIntent != null) {
            standardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolveInfo = pm.resolveActivity(standardIntent, 0)
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                val label = resolveInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: requestedName
                return executeIntent(standardIntent, label)
            }
        }

        // Secondary fallback for camera
        if (canonicalKey == "camera") {
            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val captureInfo = pm.resolveActivity(captureIntent, 0)
            if (captureInfo != null && captureInfo.activityInfo != null) {
                val label = captureInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: requestedName
                return executeIntent(captureIntent, label)
            }
        }

        return null
    }

    fun canLaunchActivityDirectly(): Boolean {
        // 1. If an activity is currently in foreground, direct launch is permitted
        if (AppForegroundTracker.isAppInForeground) {
            return true
        }

        // 2. Android 9 (API 28) and below did not enforce BAL blocks
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }

        // 3. Android 10+ permits background launch if the app has SYSTEM_ALERT_WINDOW (overlay) permission
        if (Settings.canDrawOverlays(context)) {
            return true
        }

        // Otherwise, Android 10+ Background Activity Launch (BAL) restrictions block startActivity
        return false
    }

    private fun postBackgroundLaunchNotification(intent: Intent, targetName: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "jarvis_action_launch_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "JARVIS Action Launch",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Interactive notifications to launch activities requested via voice while JARVIS is in the background"
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                targetName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder.setContentTitle("Open $targetName")
                .setContentText("Tap to open $targetName, sir.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_send, "Open $targetName", pendingIntent)
                .setPriority(Notification.PRIORITY_HIGH)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
            }

            notificationManager.notify(targetName.hashCode(), builder.build())
            AuraLogger.i(LogCategory.ACTION, "[BAL] Posted interactive launch notification for $targetName")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to post background launch notification for $targetName", e)
        }
    }

    private fun executeIntent(intent: Intent, targetName: String): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolveInfo = pm.resolveActivity(intent, 0)
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            AuraLogger.w(LogCategory.ACTION, "Intent failed to resolve to an activity for $targetName")
            return ActionResult.Failure("Sorry, sir. I couldn't open $targetName.")
        }

        // Check if Android allows launching activities directly from current process state
        if (!canLaunchActivityDirectly()) {
            AuraLogger.w(LogCategory.ACTION, "[BAL] Direct activity launch for $targetName blocked by Android background restrictions. Posting notification.")
            postBackgroundLaunchNotification(intent, targetName)
            return ActionResult.Success("Sir, please tap the notification to open $targetName due to Android security restrictions.")
        }

        return try {
            context.startActivity(intent)
            AuraLogger.i(LogCategory.ACTION, "Successfully initiated launch for $targetName")
            ActionResult.Success("Opening $targetName, sir.")
        } catch (e: android.content.ActivityNotFoundException) {
            AuraLogger.e(LogCategory.ACTION, "ActivityNotFoundException when launching $targetName", e)
            ActionResult.Failure("Sorry, sir. I couldn't open $targetName.")
        } catch (e: SecurityException) {
            AuraLogger.e(LogCategory.ACTION, "SecurityException when launching $targetName", e)
            ActionResult.Failure("Sir, permission was denied to open $targetName.")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to launch $targetName", e)
            ActionResult.Failure("Sorry, sir. I couldn't open $targetName.")
        }
    }
}
