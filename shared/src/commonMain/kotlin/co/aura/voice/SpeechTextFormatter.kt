package co.aura.voice

object SpeechTextFormatter {
    /**
     * Formats AI responses to be suitable for voice synthesis by removing Markdown,
     * lists, excessive formatting, and emojis while maintaining natural pauses.
     */
    fun format(text: String): String {
        if (text.isBlank()) return ""

        // 1. Remove markdown characters (asterisks, underscores, backticks, hashes)
        var clean = text
            .replace(Regex("\\*\\*|\\*|_|`|#"), "") // Remove bold, italic, inline code, headers
            .replace(Regex("(?m)^[-*+]\\s+"), "") // Remove list bullets
            .replace(Regex("(?m)^\\d+\\.\\s+"), "") // Remove numbered list prefixes
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // Replace markdown link [text](url) with text

        // 2. Convert bullet lists, newlines, or colons to natural pause indicators (periods/commas)
        clean = clean.replace(":", ". ")
        clean = clean.replace("\n", ". ")

        // 3. Clean up multiple punctuation marks (e.g. "..." -> ".") and spaces between periods
        clean = clean.replace(Regex("\\.{2,}"), ".")
        clean = clean.replace(Regex("\\.\\s*\\."), ".")

        // 4. Remove special characters and symbols that are read awkwardly by TTS
        clean = clean.replace(Regex("[~=>\\|\\{\\}\\[\\]\\(\\)<>]"), " ")

        // 5. Filter out emojis (Unicode range for symbols, pictographs, etc.)
        clean = clean.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")

        // 6. Normalize spacing
        clean = clean.replace(Regex("\\s+"), " ").trim()

        return clean
    }
}
