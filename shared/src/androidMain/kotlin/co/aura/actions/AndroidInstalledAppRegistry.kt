package co.aura.actions

import android.content.Context
import android.content.Intent
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory

class AndroidInstalledAppRegistry(
    private val context: Context
) : InstalledAppRegistry {

    private var cachedApps: List<InstalledApp> = emptyList()

    init {
        refreshRegistry()
    }

    @Synchronized
    override fun getInstalledApps(): List<InstalledApp> {
        if (cachedApps.isEmpty()) {
            refreshRegistry()
        }
        return cachedApps
    }

    @Synchronized
    override fun refreshRegistry() {
        AuraLogger.i(LogCategory.ACTION, "Refreshing installed apps registry with capability discovery...")
        try {
            val pm = context.packageManager

            // 1. Discover all launchable launcher apps
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherInfos = pm.queryIntentActivities(launcherIntent, 0)
            val appCapabilityMap = mutableMapOf<String, MutableSet<AppCapability>>()
            val appLabelMap = mutableMapOf<String, String>()

            for (info in launcherInfos) {
                val label = info.loadLabel(pm).toString()
                val packageName = info.activityInfo.packageName
                if (label.isNotBlank() && packageName.isNotBlank()) {
                    appLabelMap[packageName] = label
                    appCapabilityMap.getOrPut(packageName) { mutableSetOf() }.add(AppCapability.LAUNCH)
                }
            }

            // 2. Discover apps capable of media search & play (MEDIA_SEARCH / MEDIA_PLAY)
            val mediaSearchIntent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
            val mediaSearchInfos = pm.queryIntentActivities(mediaSearchIntent, 0)
            for (info in mediaSearchInfos) {
                val packageName = info.activityInfo.packageName
                if (packageName.isNotBlank()) {
                    val caps = appCapabilityMap.getOrPut(packageName) { mutableSetOf() }
                    caps.add(AppCapability.MEDIA_SEARCH)
                    caps.add(AppCapability.MEDIA_PLAY)
                    caps.add(AppCapability.MEDIA_CONTROL)
                }
            }

            // 3. Discover apps registered for audio intents / music category
            val audioViewIntent = Intent(Intent.ACTION_VIEW).apply { setType("audio/*") }
            val audioInfos = pm.queryIntentActivities(audioViewIntent, 0)
            for (info in audioInfos) {
                val packageName = info.activityInfo.packageName
                if (packageName.isNotBlank()) {
                    val caps = appCapabilityMap.getOrPut(packageName) { mutableSetOf() }
                    caps.add(AppCapability.MEDIA_PLAY)
                    caps.add(AppCapability.MEDIA_CONTROL)
                }
            }

            // 4. Infer media capabilities from package name / label keywords for local players
            val musicKeywords = listOf("music", "audio", "player", "sound", "spotify", "youtube", "vlc", "poweramp", "musicolet", "retro", "shuttle", "blackplayer", "aimp", "pulsar", "song", "media")
            for ((pkg, label) in appLabelMap) {
                val lowerPkg = pkg.lowercase()
                val lowerLabel = label.lowercase()
                if (musicKeywords.any { lowerPkg.contains(it) || lowerLabel.contains(it) }) {
                    val caps = appCapabilityMap.getOrPut(pkg) { mutableSetOf() }
                    caps.add(AppCapability.MEDIA_PLAY)
                    caps.add(AppCapability.MEDIA_CONTROL)
                }
            }

            val apps = appLabelMap.map { (pkg, label) ->
                InstalledApp(
                    label = label,
                    packageName = pkg,
                    capabilities = appCapabilityMap[pkg] ?: setOf(AppCapability.LAUNCH)
                )
            }.distinctBy { it.packageName }

            cachedApps = apps
            val musicAppCount = apps.count { AppCapability.MEDIA_PLAY in it.capabilities }
            AuraLogger.i(LogCategory.ACTION, "Discovered ${apps.size} installed apps (${musicAppCount} music/media capable).")
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Failed to query package manager for installed apps", e)
        }
    }
}
