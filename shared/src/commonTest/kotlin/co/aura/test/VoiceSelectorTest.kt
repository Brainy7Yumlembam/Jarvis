package co.aura.test

import co.aura.voice.TtsVoiceInfo
import co.aura.voice.VoiceSelector
import kotlin.test.*

class VoiceSelectorTest {

    @Test
    fun testMaleEnGbSelectedWhenAvailable() {
        val voices = listOf(
            TtsVoiceInfo("en-gb-x-rjs-local", "en", "GB", false), // male heuristic (6 points)
            TtsVoiceInfo("en-us-x-male-local", "en", "US", false), // male heuristic (5 points)
            TtsVoiceInfo("en-gb-x-wls-local", "en", "GB", false), // female heuristic (3 points)
            TtsVoiceInfo("en-us-x-female-local", "en", "US", false) // female heuristic (2 points)
        )
        val selected = VoiceSelector.selectBestJarvisVoice(voices, null, "en-GB")
        assertNotNull(selected)
        assertEquals("en-gb-x-rjs-local", selected.name)
    }

    @Test
    fun testMaleEnUsSelectedWhenMaleEnGbMissing() {
        val voices = listOf(
            TtsVoiceInfo("en-us-x-male-local", "en", "US", false), // male heuristic (5 points)
            TtsVoiceInfo("en-gb-x-wls-local", "en", "GB", false), // female heuristic (3 points)
            TtsVoiceInfo("en-us-x-female-local", "en", "US", false) // female heuristic (2 points)
        )
        val selected = VoiceSelector.selectBestJarvisVoice(voices, null, "en-GB")
        assertNotNull(selected)
        assertEquals("en-us-x-male-local", selected.name)
    }

    @Test
    fun testFallbackToEnGbWhenNoMaleVoicesFound() {
        val voices = listOf(
            TtsVoiceInfo("en-gb-x-wls-local", "en", "GB", false), // female heuristic (3 points)
            TtsVoiceInfo("en-us-x-female-local", "en", "US", false) // female heuristic (2 points)
        )
        val selected = VoiceSelector.selectBestJarvisVoice(voices, null, "en-GB")
        assertNotNull(selected)
        assertEquals("en-gb-x-wls-local", selected.name)
    }

    @Test
    fun testPreferredVoiceWins() {
        val voices = listOf(
            TtsVoiceInfo("en-gb-x-rjs-local", "en", "GB", false), // male (6 points)
            TtsVoiceInfo("en-us-x-male-local", "en", "US", false), // male (5 points)
            TtsVoiceInfo("en-gb-x-mycustomvoice", "en", "GB", false) // target preferred voice
        )
        val selected = VoiceSelector.selectBestJarvisVoice(voices, "en-gb-x-mycustomvoice", "en-GB")
        assertNotNull(selected)
        assertEquals("en-gb-x-mycustomvoice", selected.name)
    }

    @Test
    fun testPreferredVoiceUnavailableUsesMaleFallback() {
        val voices = listOf(
            TtsVoiceInfo("en-gb-x-rjs-local", "en", "GB", false), // male (6 points)
            TtsVoiceInfo("en-us-x-male-local", "en", "US", false)  // male (5 points)
        )
        val selected = VoiceSelector.selectBestJarvisVoice(voices, "non-existent-voice", "en-GB")
        assertNotNull(selected)
        assertEquals("en-gb-x-rjs-local", selected.name)
    }

    @Test
    fun testNoEnglishVoicesGracefullyFallsBackToDefault() {
        val voices = listOf(
            TtsVoiceInfo("fr-fr-x-fr-local", "fr", "FR", false),
            TtsVoiceInfo("es-es-x-es-local", "es", "ES", false)
        )
        val selected = VoiceSelector.selectBestJarvisVoice(voices, null, "en-GB")
        assertNotNull(selected)
        assertEquals("fr-fr-x-fr-local", selected.name) // first in the list
    }
}
