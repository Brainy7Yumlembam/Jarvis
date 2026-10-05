package co.aura.agent

import co.aura.conversation.ConversationMode

/**
 * Registry of available agent tools and action schemas.
 * Exposes compact tool/action descriptions selectively based on query intent and conversation mode.
 */
interface AgentToolRegistry {
    /**
     * Returns the list of tool schemas relevant to the query and mode.
     * In pure CONVERSATION mode, returns emptyList() to save tokens.
     */
    fun getRelevantToolSchemas(query: String, mode: ConversationMode): List<String>

    /**
     * Returns all available tool schemas (compact).
     */
    fun getAllToolSchemas(): List<String>

    /**
     * Builds the JSON format instructions if tools are present.
     */
    fun buildFormatInstructions(toolSchemas: List<String>): String
}

class AgentToolRegistryImpl : AgentToolRegistry {

    private val toolSchemas = mapOf(
        "OPEN_APP" to "\"OPEN_APP\": Opens an installed app. Params: { \"appName\": \"<app name>\" }",
        "SET_ALARM" to "\"SET_ALARM\": Sets an alarm. Params: { \"hour\": <0-23>, \"minute\": <0-59>, \"label\": \"<optional string or null>\" }",
        "SET_TIMER" to "\"SET_TIMER\": Sets a countdown timer. Params: { \"durationSeconds\": <seconds as integer>, \"label\": \"<optional string or null>\" }",
        "CALL_CONTACT" to "\"CALL_CONTACT\": Calls a contact (name only, NO phone number). Params: { \"contactName\": \"<name>\" }",
        "SEND_SMS" to "\"SEND_SMS\": Sends SMS (name only, NO phone number). Params: { \"contactName\": \"<name>\", \"message\": \"<text>\" }",
        "SEND_WHATSAPP" to "\"SEND_WHATSAPP\": Sends WhatsApp (name only, NO phone number). Params: { \"contactName\": \"<name>\", \"message\": \"<text>\" }",
        "PLAY_MEDIA" to "\"PLAY_MEDIA\": Plays song/artist/playlist. Params: { \"query\": \"<search query>\", \"source\": \"<optional app or null>\" }",
        "PAUSE_MEDIA" to "\"PAUSE_MEDIA\": Pauses media. Params: {}",
        "RESUME_MEDIA" to "\"RESUME_MEDIA\": Resumes media. Params: {}",
        "SKIP_MEDIA" to "\"SKIP_MEDIA\": Next track. Params: {}",
        "PREVIOUS_MEDIA" to "\"PREVIOUS_MEDIA\": Previous track. Params: {}",
        "STOP_MEDIA" to "\"STOP_MEDIA\": Stops media. Params: {}",
        "VOLUME_UP" to "\"VOLUME_UP\": Increases volume. Params: {}",
        "VOLUME_DOWN" to "\"VOLUME_DOWN\": Decreases volume. Params: {}",
        "SET_VOLUME" to "\"SET_VOLUME\": Sets volume level. Params: { \"level\": <0-100> }",
        "TOGGLE_FLASHLIGHT" to "\"TOGGLE_FLASHLIGHT\": Flashlight on/off. Params: { \"enabled\": true | false }",
        "TAKE_SCREENSHOT" to "\"TAKE_SCREENSHOT\": Captures screenshot. Params: {}",
        "GET_BATTERY" to "\"GET_BATTERY\": Checks battery level. Params: {}",
        "GET_CURRENT_TIME" to "\"GET_CURRENT_TIME\": Checks system time. Params: {}"
    )

    override fun getRelevantToolSchemas(query: String, mode: ConversationMode): List<String> {
        if (mode == ConversationMode.CONVERSATION) {
            return emptyList()
        }

        val lower = query.lowercase().trim()

        // Pure conversational/knowledge queries do not need action schemas
        if (isPureConversationalQuery(lower)) {
            return emptyList()
        }

        val matchedTools = mutableSetOf<String>()

        // Media matching
        if (lower.contains("play") || lower.contains("song") || lower.contains("music") ||
            lower.contains("spotify") || lower.contains("youtube") || lower.contains("track") ||
            lower.contains("listen") || lower.contains("artist") || lower.contains("album")
        ) {
            matchedTools.add("PLAY_MEDIA")
            matchedTools.add("PAUSE_MEDIA")
            matchedTools.add("RESUME_MEDIA")
            matchedTools.add("SKIP_MEDIA")
            matchedTools.add("STOP_MEDIA")
        }

        if (lower.contains("pause") || lower.contains("resume") || lower.contains("skip") ||
            lower.contains("next song") || lower.contains("previous song") || lower.contains("stop music")
        ) {
            matchedTools.add("PAUSE_MEDIA")
            matchedTools.add("RESUME_MEDIA")
            matchedTools.add("SKIP_MEDIA")
            matchedTools.add("PREVIOUS_MEDIA")
            matchedTools.add("STOP_MEDIA")
        }

        // Volume matching
        if (lower.contains("volume") || lower.contains("louder") || lower.contains("quieter") ||
            lower.contains("mute") || lower.contains("unmute") || lower.contains("sound level")
        ) {
            matchedTools.add("VOLUME_UP")
            matchedTools.add("VOLUME_DOWN")
            matchedTools.add("SET_VOLUME")
        }

        // App matching
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.contains(" open ") ||
            lower.contains(" launch ") || lower.contains("start app")
        ) {
            matchedTools.add("OPEN_APP")
        }

        // Alarm / Timer matching
        if (lower.contains("alarm") || lower.contains("wake me")) {
            matchedTools.add("SET_ALARM")
        }
        if (lower.contains("timer") || lower.contains("countdown") || lower.contains("set a timer")) {
            matchedTools.add("SET_TIMER")
        }

        // Communication matching
        if (lower.contains("call ") || lower.contains("phone ") || lower.contains("dial ")) {
            matchedTools.add("CALL_CONTACT")
        }
        if (lower.contains("sms") || lower.contains("text ") || lower.contains("message ")) {
            matchedTools.add("SEND_SMS")
            matchedTools.add("SEND_WHATSAPP")
        }
        if (lower.contains("whatsapp")) {
            matchedTools.add("SEND_WHATSAPP")
        }

        // System / Device matching
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")) {
            matchedTools.add("TOGGLE_FLASHLIGHT")
        }
        if (lower.contains("screenshot") || lower.contains("screen capture")) {
            matchedTools.add("TAKE_SCREENSHOT")
        }
        if (lower.contains("battery") || lower.contains("power level") || lower.contains("charge percentage")) {
            matchedTools.add("GET_BATTERY")
        }
        if (lower.contains("what time") || lower.contains("what's the time") || lower.contains("current time")) {
            matchedTools.add("GET_CURRENT_TIME")
        }

        // If specific tools were identified, return only those
        if (matchedTools.isNotEmpty()) {
            return matchedTools.mapNotNull { toolSchemas[it] }
        }

        // If in COMMAND mode and not recognized as pure conversation, return all tools
        if (mode == ConversationMode.COMMAND) {
            return getAllToolSchemas()
        }

        // In HYBRID mode, if no action keyword is matched, omit tools to save tokens
        return emptyList()
    }

    override fun getAllToolSchemas(): List<String> {
        return toolSchemas.values.toList()
    }

    override fun buildFormatInstructions(toolSchemas: List<String>): String {
        if (toolSchemas.isEmpty()) {
            return "Format: Respond naturally in plain spoken conversational text. Do not wrap response in JSON unless explicitly asked."
        }

        return buildString {
            append("Respond with JSON object:\n")
            append("{\n")
            append("  \"type\": \"NORMAL_CONVERSATION\" | \"ACTION\" | \"HYBRID\",\n")
            append("  \"response\": \"Spoken conversational response\",\n")
            append("  \"actions\": [ { \"action\": \"<name>\", \"parameters\": { ... } } ]\n")
            append("}\n\n")
            append("Available Actions for this request:\n")
            toolSchemas.forEachIndexed { index, schema ->
                append("${index + 1}. $schema\n")
            }
            append("Security: NEVER include phone numbers in action parameters. Contact names only.")
        }
    }

    private fun isPureConversationalQuery(lower: String): Boolean {
        val pureConversationalPhrases = listOf(
            "who are you", "what are you", "who made you", "who built you", "who created you",
            "tell me a joke", "tell a joke", "make me laugh",
            "how are you", "how are you doing", "hello", "hi jarvis", "hey jarvis",
            "good morning", "good evening", "good afternoon", "good night",
            "what is 2", "what's 2", "explain ", "what is ", "who is ", "why is ", "how does ",
            "tell me about", "what do you think of", "write a", "summarize"
        )
        // Check if query starts with or equals conversational phrases
        return pureConversationalPhrases.any { phrase ->
            lower == phrase || lower.startsWith(phrase)
        } && !lower.contains("alarm") && !lower.contains("timer") && !lower.contains("call ") &&
           !lower.contains("sms") && !lower.contains("whatsapp") && !lower.contains("play ") &&
           !lower.contains("flashlight") && !lower.contains("open ")
    }
}
