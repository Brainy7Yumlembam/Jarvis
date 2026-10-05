package co.aura.voice

/**
 * Detects user interruption phrases during speech output.
 */
interface InterruptionDetector {
    /**
     * Returns true if the recognized speech contains a deliberate interruption phrase.
     */
    fun detect(text: String): Boolean
}

class DefaultInterruptionDetector : InterruptionDetector {
    // Distinct word boundaries and phrase patterns to avoid false positives on normal speech
    private val strongInterruptionPatterns = listOf(
        Regex("""\b(stop|stop\s+talking|shut\s+up|be\s+quiet|shut\s+it|cancel|hold\s+on|wait\s+a\s+second|silence|hush|enough)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(wait|wait\s+wait|wait\s+up|wait\s+a\s+minute)\b""", RegexOption.IGNORE_CASE)
    )

    override fun detect(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim().removeSuffix(".").removeSuffix("!").removeSuffix("?")
        
        // Explicit check to ensure ambiguous words like "no" do not cause accidental interruptions
        // (e.g. "No, that's not what I meant" must NOT trigger an interruption)
        if (trimmed.equals("no", ignoreCase = true) || trimmed.startsWith("no,", ignoreCase = true)) {
            return false
        }

        return strongInterruptionPatterns.any { it.containsMatchIn(trimmed) }
    }
}
