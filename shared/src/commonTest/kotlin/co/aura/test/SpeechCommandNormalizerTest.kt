package co.aura.test

import co.aura.voice.SpeechCommandNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechCommandNormalizerTest {

    private val normalizer = SpeechCommandNormalizer()

    @Test
    fun testNormalizationVariations() {
        assertEquals("youtube", normalizer.normalize("you tube"))
        assertEquals("youtube", normalizer.normalize("you-tube"))
        assertEquals("whatsapp", normalizer.normalize("watsapp"))
        assertEquals("whatsapp", normalizer.normalize("whats app"))
        assertEquals("whatsapp", normalizer.normalize("what's app"))
        assertEquals("whatsapp", normalizer.normalize("what app"))
        assertEquals("camera", normalizer.normalize("cam"))
        assertEquals("instagram", normalizer.normalize("insta"))
        assertEquals("facebook", normalizer.normalize("fb"))
        assertEquals("messenger", normalizer.normalize("messeger"))
        assertEquals("telegram", normalizer.normalize("tele gram"))
        assertEquals("gmail", normalizer.normalize("g mail"))
    }

    @Test
    fun testHinglishCommandNormalization() {
        assertEquals("whatsapp kholo", normalizer.normalize("watsapp kholo"))
        assertEquals("whatsapp kholo", normalizer.normalize("whats app kholo"))
        assertEquals("camera open karo", normalizer.normalize("cam open karo"))
        assertEquals("youtube kholo", normalizer.normalize("YT kholo"))
    }

    @Test
    fun testNormalConversationIsNotDamaged() {
        val normalText = "I was talking about YouTube yesterday."
        val normalized = normalizer.normalize(normalText)
        assertEquals("i was talking about youtube yesterday", normalized)
    }
}
