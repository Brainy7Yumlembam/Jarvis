package co.aura.voice

interface TextToSpeechEngine {
    suspend fun speak(text: String): Boolean
    fun stop()
    fun isSpeaking(): Boolean
}
