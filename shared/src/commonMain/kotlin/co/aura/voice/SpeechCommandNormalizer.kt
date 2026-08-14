package co.aura.voice

class SpeechCommandNormalizer {

    fun normalize(text: String): String {
        var normalized = text.lowercase().trim()
        
        // Remove apostrophes, punctuation
        normalized = normalized
            .replace("'", "")
            .replace("`", "")
            .replace("-", " ")
            .replace(Regex("[,.?!]"), "")

        // Hinglish / Spoken phrases pre-processing
        normalized = normalized
            .replace(Regex("""\b(call|phone)\s+my\s+([a-zA-Z0-9\s]+?)\s*(please)?$""")) { match ->
                "call ${match.groupValues[2].trim()}"
            }
            .replace(Regex("""\b(call|phone)\s+([a-zA-Z0-9\s]+?)\s+please$""")) { match ->
                "call ${match.groupValues[2].trim()}"
            }
            .replace(Regex("""\bphone\s+([a-zA-Z0-9\s]+)\b""")) { match ->
                "call ${match.groupValues[1].trim()}"
            }
            .replace(Regex("""\bsend\s+a\s+whatsapp\s+to\s+([a-zA-Z0-9\s]+)\b""")) { match ->
                "send whatsapp to ${match.groupValues[1].trim()}"
            }
            .replace(Regex("""\bwhatsapp\s+([a-zA-Z0-9\s]+)\b""")) { match ->
                "send whatsapp to ${match.groupValues[1].trim()}"
            }
            .replace(Regex("""\bmessage\s+([a-zA-Z0-9\s]+)\b""")) { match ->
                "send sms to ${match.groupValues[1].trim()}"
            }
            .replace(Regex("""\b(mummy|mom|maa)\s+ko\s+call\s+(karo|karna)\b"""), "call mom")
            .replace(Regex("""\b(papa|dad|father)\s+ko\s+(phone|call)\s+(karo|karna)\b"""), "call papa")
            .replace(Regex("""\b([a-zA-Z0-9\s]+)\s+ko\s+(call|phone)\s+(karo|karna)\b""")) { match ->
                "call ${match.groupValues[1].trim()}"
            }
            .replace(Regex("""\b([a-zA-Z0-9]+)\s+(pe|par)\s+(.+)\s+(chalao|bajao|play)\b""")) { match ->
                "play ${match.groupValues[3].trim()} on ${match.groupValues[1].trim()}"
            }
        
        // Split into words, apply key spelling changes, and join
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val processedWords = mutableListOf<String>()
        
        var i = 0
        while (i < words.size) {
            val word = words[i]
            
            // Check for multi-word phrases to normalize
            if ((word == "you" || word == "u") && i + 1 < words.size && words[i + 1] == "tube") {
                processedWords.add("youtube")
                i += 2
                continue
            }
            if ((word == "whats" || word == "what") && i + 1 < words.size && words[i + 1] == "app") {
                processedWords.add("whatsapp")
                i += 2
                continue
            }
            if (word == "tele" && i + 1 < words.size && words[i + 1] == "gram") {
                processedWords.add("telegram")
                i += 2
                continue
            }
            if (word == "g" && i + 1 < words.size && words[i + 1] == "mail") {
                processedWords.add("gmail")
                i += 2
                continue
            }
            
            // Single word replacements
            val replaced = when (word) {
                "watsapp", "watsp", "whatsappapp" -> "whatsapp"
                "ytube", "utube" -> "youtube"
                "cam", "camra" -> "camera"
                "insta" -> "instagram"
                "fb" -> "facebook"
                "messeger" -> "messenger"
                "yt" -> "youtube"
                else -> word
            }
            processedWords.add(replaced)
            i++
        }
        
        return processedWords.joinToString(" ")
    }
}
