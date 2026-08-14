package co.aura.test

import co.aura.voice.SpeechTextFormatter
import kotlin.test.*

class SpeechTextFormatterTest {

    @Test
    fun testMarkdownFormattingRemoval() {
        val input = "Sure! Here is **bold** and *italic* text. Check `code` block."
        val expected = "Sure! Here is bold and italic text. Check code block."
        assertEquals(expected, SpeechTextFormatter.format(input))
    }

    @Test
    fun testBulletListConversion() {
        val input = "Here are the items:\n- First item\n- Second item"
        val expected = "Here are the items. First item. Second item"
        assertEquals(expected, SpeechTextFormatter.format(input))
    }

    @Test
    fun testExcessivePunctuationAndSpacing() {
        val input = "Hold on...  processing... done! "
        val expected = "Hold on. processing. done!"
        assertEquals(expected, SpeechTextFormatter.format(input))
    }

    @Test
    fun testEmojiRemoval() {
        val input = "Hello world! 😊🚀 Have a great day!"
        val expected = "Hello world! Have a great day!"
        assertEquals(expected, SpeechTextFormatter.format(input))
    }
}
