package co.aura.voice

class OpenAITTSEngine : TextToSpeechEngine {
    override suspend fun speak(text: String): Boolean = true
    override fun stop() {}
    override fun isSpeaking(): Boolean = false
}
