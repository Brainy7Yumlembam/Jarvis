package co.aura.phone

interface SystemControlProvider {
    fun setVolume(level: Int)
    fun setBrightness(level: Int)
    fun getInstalledApps(): List<String>
}

class SystemControlProviderImpl : SystemControlProvider {
    override fun setVolume(level: Int) {
        // TODO: Bridge to AudioManager (Android) or system shell volumes (Desktop)
    }

    override fun setBrightness(level: Int) {
        // TODO: Bridge to brightness parameters
    }

    override fun getInstalledApps(): List<String> {
        return emptyList()
    }
}
