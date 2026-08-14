package co.aura.voice

data class JarvisVoiceProfile(
    val locale: String = "en-GB",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val preferredVoiceName: String? = null
)
