package co.aura.agent

import co.aura.actions.*
import co.aura.conversation.AssistantPersonality
import co.aura.conversation.ConversationMode
import co.aura.conversation.PersonalityEngine
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryFragment
import co.aura.domain.model.MessageSender
import co.aura.memory.MemoryManager
import co.aura.voice.SpeechCommandNormalizer

/**
 * Result of the local agent triage/planning phase.
 */
sealed interface AgentPlanResult {
    /** Request can be fulfilled locally without contacting Gemini */
    data class DirectResponse(val responseText: String, val action: Action? = null) : AgentPlanResult

    /** Request requires AI generation/streaming with a compact, targeted context */
    data class RequiresAi(val agentContext: AgentContext) : AgentPlanResult
}

/**
 * Intelligent lightweight Agent coordinator.
 * Triage requests locally and builds minimal targeted AgentContexts to minimize token consumption.
 */
interface JarvisAgent {
    suspend fun plan(
        userText: String,
        conversationMode: ConversationMode,
        inMemoryHistory: List<ChatMessage>,
        recentActionContext: String?,
        conversationSummary: String?
    ): AgentPlanResult
}

class JarvisAgentImpl(
    private val memoryManager: MemoryManager,
    private val toolRegistry: AgentToolRegistry,
    private val personalityEngine: PersonalityEngine,
    private val speechNormalizer: SpeechCommandNormalizer
) : JarvisAgent {

    override suspend fun plan(
        userText: String,
        conversationMode: ConversationMode,
        inMemoryHistory: List<ChatMessage>,
        recentActionContext: String?,
        conversationSummary: String?
    ): AgentPlanResult {
        val rawCleaned = userText.trim().removeSuffix(".").removeSuffix("?").removeSuffix("!")
        val rawLower = rawCleaned.lowercase()

        val normalized = speechNormalizer.normalize(userText)
        val cleaned = normalized.trim().removeSuffix(".").removeSuffix("?").removeSuffix("!")
        val lower = cleaned.lowercase()

        // ── 1. Direct Local Memory Commands ──────────────────────────────────────────
        // 1a. Remember (Preserve original user text casing)
        val rememberPrefixes = listOf("remember that ", "remember ")
        for (prefix in rememberPrefixes) {
            if (rawLower.startsWith(prefix)) {
                val content = rawCleaned.substring(prefix.length).trim()
                if (content.isNotEmpty()) {
                    val score = memoryManager.scoreMemoryImportance(content)
                    val success = memoryManager.storeMemory(content, "PREFERENCE", score)
                    val responseText = if (success) {
                        "I will remember: $content"
                    } else {
                        "Sorry, I couldn't save that memory. (It may contain sensitive credentials)."
                    }
                    return AgentPlanResult.DirectResponse(responseText)
                }
            }
        }

        // 1b. Forget (Preserve original user target)
        val forgetPrefixes = listOf("forget that ", "forget ")
        for (prefix in forgetPrefixes) {
            if (rawLower.startsWith(prefix)) {
                val target = rawCleaned.substring(prefix.length).trim()
                if (target.isNotEmpty()) {
                    val allMemories = memoryManager.searchMemories("")
                    val matches = allMemories.filter { it.content.lowercase().contains(target.lowercase()) }
                    val responseText = when {
                        matches.isEmpty() -> "I couldn't find any memory matching: \"$target\"."
                        matches.size == 1 -> {
                            memoryManager.forgetMemory(matches.first().id)
                            "I have forgotten: \"${matches.first().content}\"."
                        }
                        else -> {
                            "I found multiple memories related to \"$target\". Which one would you like me to forget?\n" +
                                    matches.map { "- ${it.content}" }.joinToString("\n")
                        }
                    }
                    return AgentPlanResult.DirectResponse(responseText)
                }
            }
        }

        // 1c. Query Memory
        val memoryQueryPhrases = listOf(
            "what do you remember about me",
            "show me what you remember about me",
            "what do you remember",
            "list my memories",
            "what is in your memory"
        )
        if (memoryQueryPhrases.any { lower == it || lower.startsWith(it) || rawLower == it || rawLower.startsWith(it) }) {
            val allMemories = memoryManager.searchMemories("")
            val responseText = if (allMemories.isEmpty()) {
                "I don't have any saved memories about you yet."
            } else {
                "Here is what I remember about you:\n" +
                        allMemories.map { "- ${it.content}" }.joinToString("\n")
            }
            return AgentPlanResult.DirectResponse(responseText)
        }

        // ── 2. Direct Deterministic Device Control (Zero Token Fast Path) ─────────────
        if (conversationMode != ConversationMode.CONVERSATION) {
            when {
                lower == "turn on flashlight" || lower == "flashlight on" || lower == "torch on" ->
                    return AgentPlanResult.DirectResponse("Turning on the flashlight, sir.", ToggleFlashlightAction(true))
                lower == "turn off flashlight" || lower == "flashlight off" || lower == "torch off" ->
                    return AgentPlanResult.DirectResponse("Turning off the flashlight, sir.", ToggleFlashlightAction(false))
                lower == "pause music" || lower == "pause" || lower == "pause media" ->
                    return AgentPlanResult.DirectResponse("Pausing playback, sir.", PauseMediaAction())
                lower == "resume music" || lower == "resume" || lower == "resume media" ->
                    return AgentPlanResult.DirectResponse("Resuming playback, sir.", ResumeMediaAction())
                lower == "stop music" || lower == "stop media" ->
                    return AgentPlanResult.DirectResponse("Stopping playback, sir.", StopMediaAction())
                lower == "next track" || lower == "next song" || lower == "skip song" ->
                    return AgentPlanResult.DirectResponse("Skipping to next track, sir.", SkipMediaAction())
                lower == "previous track" || lower == "previous song" ->
                    return AgentPlanResult.DirectResponse("Going to previous track, sir.", PreviousMediaAction())
                lower == "volume up" || lower == "louder" || lower == "turn up the volume" ->
                    return AgentPlanResult.DirectResponse("Increasing volume, sir.", VolumeUpAction())
                lower == "volume down" || lower == "quieter" || lower == "turn down the volume" ->
                    return AgentPlanResult.DirectResponse("Decreasing volume, sir.", VolumeDownAction())
                lower == "take screenshot" || lower == "take a screenshot" || lower == "capture screen" ->
                    return AgentPlanResult.DirectResponse("Taking screenshot, sir.", TakeScreenshotAction())
                lower == "what is the battery" || lower == "battery level" || lower == "battery percentage" ->
                    return AgentPlanResult.DirectResponse("Checking battery level, sir.", GetBatteryAction())
            }
        }

        // ── 3. Selective Memory Retrieval ────────────────────────────────────────────
        val requiresMemory = shouldRetrieveMemory(lower)
        val selectedMemories: List<MemoryFragment> = if (requiresMemory) {
            try {
                memoryManager.retrieveRelevantMemories(normalized, limit = 3)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // ── 4. Selective Conversation History Window ─────────────────────────────────
        val selectedHistory = selectRelevantHistory(lower, inMemoryHistory)

        // ── 5. Selective Tool Schemas ─────────────────────────────────────────────────
        val toolSchemas = toolRegistry.getRelevantToolSchemas(normalized, conversationMode)

        // ── 6. Compact System / Identity Instructions ────────────────────────────────
        val personality = personalityEngine.getPersonality()
        val baseSystemInstruction = buildBaseSystemInstructions(personality, conversationMode, toolSchemas)

        val context = AgentContext(
            systemInstructions = baseSystemInstruction,
            personalityPrompt = null,
            selectedMemories = selectedMemories,
            selectedRecentMessages = selectedHistory,
            selectedToolSchemas = toolSchemas,
            conversationMode = conversationMode,
            recentActionContext = recentActionContext,
            conversationSummary = if (selectedHistory.size > 2) conversationSummary else null,
            currentRequest = normalized
        )

        return AgentPlanResult.RequiresAi(context)
    }

    /**
     * Determines whether the user request requires personal persistent memory retrieval.
     */
    private fun shouldRetrieveMemory(lower: String): Boolean {
        val memoryMarkers = listOf(
            "my project", "my name", "my favorite", "my preference", "about me",
            "what do i like", "what do i prefer", "where do i", "what do i work",
            "who is my", "what is my", "am i working on", "did i tell you",
            "do you remember", "my routine", "my job", "my car", "my phone",
            "my email", "my address", "my home"
        )
        if (memoryMarkers.any { lower.contains(it) }) return true

        if ((lower.contains(" my ") || lower.startsWith("my ")) &&
            (lower.contains("what") || lower.contains("who") || lower.contains("where") || lower.contains("which") || lower.contains("tell"))
        ) {
            return true
        }

        return false
    }

    /**
     * Selects an optimal, bounded conversation history window.
     */
    private fun selectRelevantHistory(lower: String, history: List<ChatMessage>): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()

        val contextMarkers = listOf(
            " it", " this", " that", " them", " they", " he", " she", " him", " her",
            "the first", "the second", "the last", "again", "another one", "why", "how so"
        )
        val isContextDependent = contextMarkers.any { lower.contains(it) } || lower.split(" ").size <= 3

        return if (isContextDependent) {
            history.takeLast(6)
        } else {
            history.takeLast(2)
        }
    }

    private fun buildBaseSystemInstructions(
        personality: AssistantPersonality,
        mode: ConversationMode,
        toolSchemas: List<String>
    ): String {
        return buildString {
            append("You are ${personality.name}, an AI assistant built by ${personality.creatorName}.\n")
            append("Tone: ${personality.tone}. Style: ${personality.style}.\n")
            append("Creator / Developer / Maker: ${personality.creatorName} (\"Brainy\"). Brainy is your sole creator and developer. Tony Stark is only a fictional Marvel character; Brainy is your real creator. If asked who built/created/developed you, state that Brainy built you.\n")
            append("Address the user respectfully as 'Sir' when natural.\n")
            append("Mode: $mode.\n\n")

            if (toolSchemas.isNotEmpty()) {
                append(toolRegistry.buildFormatInstructions(toolSchemas))
            } else {
                append("Format: Respond in concise, spoken natural language. Do NOT output JSON.")
            }
        }
    }
}
