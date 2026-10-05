package co.aura.conversation

/**
 * Intelligent stream tokenizer that extracts complete sentences or natural phrases
 * from streamed tokens as they arrive from an LLM.
 *
 * Designed to provide the lowest latency for Text-To-Speech while avoiding:
 * - false splits on decimal numbers (e.g. 3.14, $19.99)
 * - false splits on common abbreviations (e.g. Dr., Mr., vs., e.g., i.e., etc.)
 * - false splits on ellipses (...)
 * - premature splits before sentence completion
 */
class SentenceChunker {

    private val buffer = StringBuilder()

    companion object {
        private val INITIALISM_REGEX = Regex("^([a-z]\\.)*[a-z]$")
        private val COMMON_ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "e.g", "i.e", "etc",
            "approx", "dept", "fig", "gen", "gov", "inc", "corp", "ltd", "co",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "st", "ave", "blvd", "rd", "no", "vol", "p"
        )
    }

    /**
     * Appends a newly arrived token from the stream and extracts any complete sentences.
     * Returns a list of complete sentence strings (may be empty if no full sentence is formed yet).
     */
    fun append(token: String): List<String> {
        buffer.append(token)
        return extractSentences(isFinal = false)
    }

    /**
     * Flushes any remaining text in the buffer when the stream completes.
     */
    fun flush(): List<String> {
        return extractSentences(isFinal = true)
    }

    /**
     * Resets the buffer for a new stream.
     */
    fun reset() {
        buffer.clear()
    }

    private fun extractSentences(isFinal: Boolean): List<String> {
        val results = mutableListOf<String>()

        var searchStart = 0
        while (searchStart < buffer.length) {
            val boundaryIndex = findNextBoundary(searchStart, isFinal)
            if (boundaryIndex == -1) break

            val candidate = buffer.substring(0, boundaryIndex + 1).trim()
            if (candidate.isNotEmpty()) {
                results.add(candidate)
            }

            // Remove the emitted sentence from the buffer
            buffer.delete(0, boundaryIndex + 1)
            searchStart = 0
        }

        if (isFinal) {
            val remaining = buffer.toString().trim()
            if (remaining.isNotEmpty()) {
                results.add(remaining)
            }
            buffer.clear()
        }

        return results
    }

    private fun findNextBoundary(startIndex: Int, isFinal: Boolean): Int {
        val len = buffer.length
        var i = startIndex

        while (i < len) {
            val ch = buffer[i]

            // 1. Explicit line break: always a sentence boundary
            if (ch == '\n') {
                return i
            }

            // 2. Sentence punctuation marks
            if (ch == '.' || ch == '?' || ch == '!') {
                // Ellipsis check: do not split inside "..." or ".."
                val isPrevDot = (i > 0 && buffer[i - 1] == '.')
                val isNextDot = (i + 1 < len && buffer[i + 1] == '.')
                if (isPrevDot || isNextDot) {
                    i++
                    continue
                }

                // Decimal check: e.g. 3.14 or $2.50
                if (ch == '.') {
                    val prevIsDigit = (i > 0 && buffer[i - 1].isDigit())
                    val nextIsDigit = (i + 1 < len && buffer[i + 1].isDigit())
                    if (prevIsDigit && nextIsDigit) {
                        i++
                        continue
                    }
                }

                // If not at the end of the stream, we must verify what follows the punctuation
                if (i + 1 < len) {
                    val nextChar = buffer[i + 1]
                    // Sentence boundary must be followed by whitespace, quote, or closing paren
                    val isValidFollowingChar = nextChar.isWhitespace() || nextChar == '"' || nextChar == ')' || nextChar == ']' || nextChar == '”'
                    if (!isValidFollowingChar) {
                        i++
                        continue
                    }

                    // Abbreviation check (only for period)
                    if (ch == '.') {
                        if (isAbbreviation(i)) {
                            i++
                            continue
                        }
                    }

                    // Minimum sentence length check (at least 2 non-whitespace chars)
                    val textBefore = buffer.substring(0, i + 1).trim()
                    if (textBefore.length >= 2) {
                        return i
                    }
                } else if (isFinal) {
                    // At end of stream with punctuation
                    if (ch == '.' && isAbbreviation(i)) {
                        i++
                        continue
                    }
                    val textBefore = buffer.substring(0, i + 1).trim()
                    if (textBefore.length >= 2) {
                        return i
                    }
                }
            }

            i++
        }

        return -1
    }

    private fun isAbbreviation(dotIndex: Int): Boolean {
        var wordEnd = dotIndex - 1
        while (wordEnd >= 0 && buffer[wordEnd].isWhitespace()) {
            wordEnd--
        }
        if (wordEnd < 0) return false

        var wordStart = wordEnd
        while (wordStart >= 0 && (buffer[wordStart].isLetter() || buffer[wordStart] == '.')) {
            wordStart--
        }
        wordStart++

        if (wordStart <= wordEnd) {
            val rawWord = buffer.substring(wordStart, wordEnd + 1).lowercase().trim()
            val cleanWord = rawWord.removeSuffix(".")

            if (INITIALISM_REGEX.matches(cleanWord) || INITIALISM_REGEX.matches(rawWord)) {
                return true
            }

            if (COMMON_ABBREVIATIONS.contains(cleanWord) || COMMON_ABBREVIATIONS.contains(rawWord)) {
                return true
            }
        }
        return false
    }
}
