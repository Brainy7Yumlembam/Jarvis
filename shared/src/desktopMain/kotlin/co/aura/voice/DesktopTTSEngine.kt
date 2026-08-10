package co.aura.voice

class DesktopTTSEngine : TextToSpeechEngine {
    override suspend fun speak(text: String): Boolean {
        println("[DESKTOP-TTS] Speak: $text")
        return true
    }

    override fun stop() {}

    override fun isSpeaking(): Boolean = false
}
