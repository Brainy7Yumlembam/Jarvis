package co.aura.memory

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MemoryExtractor {
    suspend fun extractAndSaveAsync(userMessage: String, assistantResponse: String): Boolean
}

class MemoryExtractorImpl(
    private val memoryManager: MemoryManager
) : MemoryExtractor {

    private val ephemeralKeywords = listOf(
        "what time", "what's the time", "what is the time",
        "weather", "temperature", "forecast",
        "tell me a joke", "tell a joke", "make me laugh",
        "how are you", "who are you", "what are you", "who made you", "who built you", "who created you",
        "hello", "hi jarvis", "hey jarvis", "good morning", "good evening", "good afternoon", "good night",
        "open ", "launch ", "turn on", "turn off", "set alarm", "set timer", "call ", "send sms", "send whatsapp",
        "play ", "pause", "resume", "skip", "stop", "volume", "take a screenshot", "battery level", "battery percentage",
        "nevermind", "cancel", "abort", "yes", "no", "ok", "okay", "sure", "thanks", "thank you"
    )

    override suspend fun extractAndSaveAsync(userMessage: String, assistantResponse: String): Boolean = withContext(Dispatchers.Default) {
        val trimmed = userMessage.trim().removeSuffix(".").removeSuffix("?").removeSuffix("!")
        val lower = trimmed.lowercase()

        // 1. Minimum sanity length
        if (trimmed.length < 8) return@withContext false

        // 2. Reject ephemeral requests & system commands
        if (ephemeralKeywords.any { lower.startsWith(it) || lower == it }) {
            return@withContext false
        }

        // Questions without personal facts are ephemeral
        if (userMessage.trim().endsWith("?") && !lower.contains("my ") && !lower.contains("i am") && !lower.contains("i like") && !lower.contains("i prefer")) {
            return@withContext false
        }

        // 3. Extract durable information patterns
        var category = "FACT"
        var extractedContent: String? = null

        when {
            // Preferences
            lower.startsWith("i prefer ") || lower.contains(" i prefer ") -> {
                category = "PREFERENCE"
                extractedContent = trimmed
            }
            lower.startsWith("i like ") || lower.contains(" i like ") -> {
                category = "PREFERENCE"
                extractedContent = trimmed
            }
            lower.startsWith("i always ") || lower.contains(" i always ") -> {
                category = "PREFERENCE"
                extractedContent = trimmed
            }
            lower.startsWith("i never ") || lower.contains(" i never ") -> {
                category = "PREFERENCE"
                extractedContent = trimmed
            }
            lower.startsWith("i hate ") || lower.contains(" i hate ") || lower.startsWith("i dislike ") -> {
                category = "PREFERENCE"
                extractedContent = trimmed
            }

            // Person / Personal Facts
            lower.startsWith("my name is ") || lower.startsWith("call me ") -> {
                category = "PERSON"
                extractedContent = trimmed
            }
            lower.startsWith("i live in ") || lower.contains(" i live in ") -> {
                category = "PERSON"
                extractedContent = trimmed
            }
            lower.startsWith("i work at ") || lower.startsWith("my job is ") || lower.startsWith("i work as ") -> {
                category = "PERSON"
                extractedContent = trimmed
            }

            // Project / Technical Context
            lower.contains("building jarvis") || lower.contains("project jarvis") || lower.contains("working on jarvis") -> {
                category = "PROJECT"
                extractedContent = trimmed
            }
            lower.startsWith("i am building ") || lower.startsWith("i'm building ") -> {
                category = "PROJECT"
                extractedContent = trimmed
            }
            lower.startsWith("i am working on ") || lower.startsWith("i'm working on ") -> {
                category = "PROJECT"
                extractedContent = trimmed
            }
            lower.contains("compose multiplatform") || lower.contains("primary ai provider") -> {
                category = "PROJECT"
                extractedContent = trimmed
            }

            // Routine
            lower.startsWith("every morning ") || lower.startsWith("every day ") || lower.startsWith("every night ") || lower.contains("my routine is ") -> {
                category = "ROUTINE"
                extractedContent = trimmed
            }
        }

        if (extractedContent == null) {
            return@withContext false
        }

        val score = memoryManager.scoreMemoryImportance(extractedContent)
        AuraLogger.i(LogCategory.AI, "[MEMORY EXTRACTOR] Automatically detected durable memory: \"$extractedContent\" ($category)")
        memoryManager.storeMemory(extractedContent, category, score)
    }
}
