package co.aura.ai

data class GeminiModelConfig(
    val modelName: String = "gemini-2.5-flash",
    val maxOutputTokens: Int = 512,
    val thinkingBudget: Int = 0
)
