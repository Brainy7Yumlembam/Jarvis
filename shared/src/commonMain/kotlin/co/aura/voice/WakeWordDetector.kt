package co.aura.voice

/**
 * Dedicated, deterministic, local wake-word detector for JARVIS.
 *
 * Primary phrase: "Wake up, JARVIS"
 * Accepted variations: "Wake up Jarvis", "Wake up, Jarvis!", "Wake up JARVIS",
 * "Jarvis wake up", "Hey Jarvis wake up", "Wake up please Jarvis", etc.
 *
 * Distinguishes true wake intent from ordinary conversational mentions of "Jarvis"
 * (e.g. "I was speaking to Jarvis earlier").
 *
 * Zero Gemini tokens. Zero network. Zero battery-draining audio streams to the cloud.
 */
object WakeWordDetector {

    private val PUNC_REGEX = Regex("""[.,?!;:'"_\-]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun normalize(rawInput: String): String {
        return rawInput
            .lowercase()
            .replace(PUNC_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Checks if the transcript contains the wake phrase intent.
     */
    fun isWakeWord(rawInput: String): Boolean {
        val norm = normalize(rawInput)
        if (norm.isBlank()) return false

        // 1. Direct equality checks on common clean patterns
        if (norm == "wake up jarvis" || norm == "wake up jarvis please" ||
            norm == "jarvis wake up" || norm == "hey jarvis wake up" ||
            norm == "hello jarvis wake up" || norm == "ok jarvis wake up" ||
            norm == "okay jarvis wake up" || norm == "wake up" ||
            norm == "wake up please"
        ) {
            return true
        }

        // 2. Phrase matching with word boundaries
        // Ensures "wake up" AND "jarvis" appear together in wake intent
        val containsWakeUp = norm.contains("wake up") || norm.contains("wakeup")
        val containsJarvis = norm.contains("jarvis")

        if (containsWakeUp && containsJarvis) {
            // Check distance/order: "wake up ... jarvis" or "jarvis ... wake up"
            val wakeUpIndex = norm.indexOf("wake up").takeIf { it >= 0 } ?: norm.indexOf("wakeup")
            val jarvisIndex = norm.indexOf("jarvis")

            val distance = kotlin.math.abs(wakeUpIndex - jarvisIndex)
            // If they are within 25 chars of each other
            if (distance <= 25) {
                return true
            }
        }

        // 3. Fallback for common speech recognizer phonetic variations
        if (norm.contains("wait up jarvis") || norm.contains("wake up travis") ||
            norm.contains("make up jarvis") || norm.contains("woke up jarvis")
        ) {
            return true
        }

        return false
    }

    private val WAKE_PREFIX_REGEX = Regex(
        """^(?:hey\s+|hello\s+|ok\s+|okay\s+)?(?:wake\s+up\s*,?\s*jarvis|wakeup\s*,?\s*jarvis|wake\s+up|jarvis\s*,?\s*wake\s+up)(?:\s+please)?[,\s!.]*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts trailing command if the user spoke the wake word and command together
     * (e.g. "Wake up Jarvis, what time is it?").
     * Returns null if it was just the wake word alone.
     */
    fun extractCommandAfterWakeWord(rawInput: String): String? {
        val trimmed = rawInput.trim()
        val match = WAKE_PREFIX_REGEX.find(trimmed) ?: return null
        if (match.range.first == 0) {
            val after = trimmed.substring(match.range.last + 1).trim()
                .removePrefix(",").removePrefix(".").removePrefix("?").removePrefix("!").trim()
            if (after.isNotBlank()) {
                return after
            }
        }
        return null
    }
}
