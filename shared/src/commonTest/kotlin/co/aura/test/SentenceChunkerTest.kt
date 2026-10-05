package co.aura.test

import co.aura.conversation.SentenceChunker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceChunkerTest {

    @Test
    fun testSimpleSentenceSplitting() {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()

        sentences.addAll(chunker.append("The capital "))
        sentences.addAll(chunker.append("of France "))
        sentences.addAll(chunker.append("is Paris. "))

        assertEquals(1, sentences.size)
        assertEquals("The capital of France is Paris.", sentences[0])

        sentences.addAll(chunker.append("It is a great city! "))
        assertEquals(2, sentences.size)
        assertEquals("It is a great city!", sentences[1])

        sentences.addAll(chunker.flush())
        assertEquals(2, sentences.size)
    }

    @Test
    fun testDecimalNumbersNotSplit() {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()

        sentences.addAll(chunker.append("Pi is approximately 3.14159 in mathematics. "))
        assertEquals(1, sentences.size)
        assertEquals("Pi is approximately 3.14159 in mathematics.", sentences[0])

        sentences.addAll(chunker.append("The item costs $19.99 today. "))
        assertEquals(2, sentences.size)
        assertEquals("The item costs $19.99 today.", sentences[1])
    }

    @Test
    fun testAbbreviationsNotSplit() {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()

        sentences.addAll(chunker.append("Dr. Smith went to Washington D.C. yesterday. "))
        assertEquals(1, sentences.size)
        assertEquals("Dr. Smith went to Washington D.C. yesterday.", sentences[0])

        sentences.addAll(chunker.append("We need supplies, e.g. pens, paper, etc. for the meeting. "))
        assertEquals(2, sentences.size)
        assertEquals("We need supplies, e.g. pens, paper, etc. for the meeting.", sentences[1])
    }

    @Test
    fun testEllipsisNotSplitPrematurely() {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()

        sentences.addAll(chunker.append("Thinking... "))
        sentences.addAll(chunker.append("Here is the answer. "))

        assertEquals(1, sentences.size)
        assertEquals("Thinking... Here is the answer.", sentences[0])
    }

    @Test
    fun testNewlineSentenceBoundary() {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()

        sentences.addAll(chunker.append("Line one\n"))
        sentences.addAll(chunker.append("Line two\n"))

        assertEquals(2, sentences.size)
        assertEquals("Line one", sentences[0])
        assertEquals("Line two", sentences[1])
    }

    @Test
    fun testFlushEmitsTrailingText() {
        val chunker = SentenceChunker()
        val sentences = mutableListOf<String>()

        sentences.addAll(chunker.append("Hello world"))
        assertTrue(sentences.isEmpty())

        sentences.addAll(chunker.flush())
        assertEquals(1, sentences.size)
        assertEquals("Hello world", sentences[0])
    }
}
