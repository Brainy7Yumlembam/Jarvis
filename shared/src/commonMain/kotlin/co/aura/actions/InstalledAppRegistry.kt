package co.aura.actions

import kotlinx.serialization.Serializable

@Serializable
enum class AppCapability {
    LAUNCH,
    MEDIA_PLAY,
    MEDIA_SEARCH,
    MEDIA_CONTROL
}

@Serializable
data class InstalledApp(
    val label: String,
    val packageName: String,
    val capabilities: Set<AppCapability> = emptySet()
)

interface InstalledAppRegistry {
    fun getInstalledApps(): List<InstalledApp>
    fun refreshRegistry()
}
