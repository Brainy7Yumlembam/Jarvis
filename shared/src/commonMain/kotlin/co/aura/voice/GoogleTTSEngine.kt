package co.aura.voice

class GoogleTTSEngine : TextToSpeechEngine {
    override suspend fun speak(text: String): Boolean = true
    override fun stop() {}
    override fun isSpeaking(): Boolean = false
}
