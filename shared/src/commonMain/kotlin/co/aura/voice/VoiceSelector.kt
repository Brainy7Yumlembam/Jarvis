package co.aura.voice

data class TtsVoiceInfo(
    val name: String,
    val localeLanguage: String,
    val localeCountry: String,
    val isNetworkRequired: Boolean,
    val features: Set<String> = emptySet()
)

object VoiceSelector {
    /**
     * Determines whether a voice name likely belongs to a male voice profile based on heuristics.
     */
    fun isHeuristicMale(voice: TtsVoiceInfo): Boolean {
        val nameLower = voice.name.lowercase()
        
        // Exclude voices with explicit female identifiers
        val isFemale = nameLower.contains("female") || 
                       nameLower.contains("woman") || 
                       nameLower.contains("female_voice") ||
                       nameLower.contains("wls")
        if (isFemale) return false
        
        // Match explicit male indicators
        return nameLower.contains("male") || 
               nameLower.contains("man") || 
               nameLower.contains("male_voice") || 
               nameLower.contains("rjs") ||
               nameLower.contains("male-") ||
               nameLower.contains("-male")
    }

    /**
     * Selects the best voice profile for JARVIS based on priority scoring.
     */
    fun selectBestJarvisVoice(
        availableVoices: List<TtsVoiceInfo>?,
        preferredVoiceName: String?,
        preferredLocale: String
    ): TtsVoiceInfo? {
        if (availableVoices.isNullOrEmpty()) return null

        // 1. Explicit user-selected preferred voice -> highest priority
        if (!preferredVoiceName.isNullOrBlank()) {
            val preferred = availableVoices.firstOrNull { it.name == preferredVoiceName }
            if (preferred != null) return preferred
        }

        // 2. Score other voices based on language and male heuristics
        val scoredVoices = availableVoices.map { voice ->
            val isMale = isHeuristicMale(voice)
            val score = when {
                // Non-English voices score 0
                voice.localeLanguage != "en" -> 0
                
                // Strong male UK heuristic
                voice.localeCountry == "GB" && isMale -> 6
                
                // Strong male US heuristic
                voice.localeCountry == "US" && isMale -> 5
                
                // Other male English
                isMale -> 4
                
                // Any English UK locale fallback
                voice.localeCountry == "GB" -> 3
                
                // Any English US locale fallback
                voice.localeCountry == "US" -> 2
                
                // Generic English fallback
                else -> 1
            }
            voice to score
        }

        // Return the English voice with the highest score
        val bestEnglish = scoredVoices
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        // 3. Fallback to system default (first available voice) if no English voice matches
        return bestEnglish ?: availableVoices.firstOrNull()
    }
}
