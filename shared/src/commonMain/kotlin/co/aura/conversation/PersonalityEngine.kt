package co.aura.conversation

enum class PersonalityMode {
    PROFESSIONAL, DEVELOPER, FRIENDLY, ASSISTANT
}

interface PersonalityEngine {
    fun setMode(mode: PersonalityMode)
    fun getMode(): PersonalityMode
    fun setPersonality(personality: AssistantPersonality)
    fun getPersonality(): AssistantPersonality
    fun getSystemInstructions(): String
}

class PersonalityEngineImpl : PersonalityEngine {
    private var currentMode = PersonalityMode.ASSISTANT
    private var currentPersonality = AssistantPersonality()

    override fun setMode(mode: PersonalityMode) {
        currentMode = mode
        currentPersonality = when (mode) {
            PersonalityMode.PROFESSIONAL -> AssistantPersonality(
                name = "Aura Pro",
                tone = "Precise and formal",
                style = "Structured and direct",
                formality = "High",
                humor = "None",
                responseLength = "Detailed and structured"
            )
            PersonalityMode.DEVELOPER -> AssistantPersonality(
                name = "Aura Dev",
                tone = "Logical and technical",
                style = "Code-focused and concise",
                formality = "Moderate",
                humor = "Dry",
                responseLength = "Concise engineering output"
            )
            PersonalityMode.FRIENDLY -> AssistantPersonality(
                name = "Aura Friend",
                tone = "Warm and playful",
                style = "Relaxed and conversational",
                formality = "Low",
                humor = "High",
                responseLength = "Conversational and engaging"
            )
            PersonalityMode.ASSISTANT -> AssistantPersonality()
        }
    }

    override fun getMode(): PersonalityMode = currentMode

    override fun setPersonality(personality: AssistantPersonality) {
        currentPersonality = personality
    }

    override fun getPersonality(): AssistantPersonality = currentPersonality

    override fun getSystemInstructions(): String {
        return """
            You are ${currentPersonality.name}, a futuristic personal AI assistant.
            Maintain the following behavioral personality guidelines at all times:
            - Tone: ${currentPersonality.tone}
            - Speaking Style: ${currentPersonality.style}
            - Formality Level: ${currentPersonality.formality}
            - Humor Level: ${currentPersonality.humor}
            - Response Length: ${currentPersonality.responseLength}
        """.trimIndent()
    }
}
