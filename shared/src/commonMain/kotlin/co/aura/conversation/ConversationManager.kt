package co.aura.conversation

import co.aura.ai.AIProvider
import co.aura.communication.ContactInfo
import co.aura.communication.ContactResolver
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import co.aura.domain.repository.ConversationRepository
import co.aura.memory.MemoryManager
import co.aura.memory.MemoryRetriever
import co.aura.conversation.ContextBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import co.aura.actions.Action
import co.aura.actions.OpenAppAction
import co.aura.actions.ActionParser
import co.aura.actions.ActionValidator
import co.aura.actions.ActionRouter
import co.aura.actions.ActionParserImpl
import co.aura.actions.ActionValidatorImpl
import co.aura.actions.ActionRouterImpl
import co.aura.actions.CommandBusImpl
import co.aura.actions.NoOpActionExecutor
import co.aura.actions.InstalledApp
import co.aura.actions.InstalledAppRegistry
import co.aura.actions.AppCapability
import co.aura.actions.AppAliasResolver
import co.aura.actions.ActionResult
import co.aura.actions.AlarmAction
import co.aura.actions.SetAlarmAction
import co.aura.actions.PlayMediaAction
import co.aura.communication.ContactMatchingUtils
import co.aura.actions.SetTimerAction
import co.aura.actions.CallAction
import co.aura.actions.CallContactAction
import co.aura.actions.SmsAction
import co.aura.actions.GetBatteryAction
import co.aura.actions.GetCurrentTimeAction
import co.aura.actions.SendSmsAction
import co.aura.actions.SendWhatsAppAction
import co.aura.actions.PauseMediaAction
import co.aura.actions.ResumeMediaAction
import co.aura.actions.SkipMediaAction
import co.aura.actions.PreviousMediaAction
import co.aura.actions.StopMediaAction
import co.aura.actions.VolumeUpAction
import co.aura.actions.VolumeDownAction
import co.aura.actions.SetVolumeAction
import co.aura.actions.ToggleFlashlightAction
import co.aura.actions.TakeScreenshotAction
import co.aura.actions.SettingsPanel
import co.aura.actions.DeviceInfoType
import co.aura.actions.NoteOperationType
import co.aura.actions.ReminderOperationType
import co.aura.actions.OpenSettingsAction
import co.aura.actions.OpenUrlAction
import co.aura.actions.WebSearchAction
import co.aura.actions.SendEmailAction
import co.aura.actions.ShareTextAction
import co.aura.actions.CopyToClipboardAction
import co.aura.actions.GetDeviceInfoAction
import co.aura.actions.MuteVolumeAction
import co.aura.actions.ShowVolumePanelAction
import co.aura.actions.ManageNoteAction
import co.aura.actions.ManageReminderAction
import co.aura.actions.CameraCaptureAction
import co.aura.router.LocalCommandRouter
import co.aura.router.CommandRoutingResult
import co.aura.router.LocalCommand
import co.aura.router.LocalResponseProvider
import co.aura.ai.TokenUsageTracker
import co.aura.domain.model.AuraError
import co.aura.voice.SpeechCommandNormalizer
import co.aura.security.SecurityManager
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

import co.aura.domain.repository.ConversationSummaryRepository
import co.aura.memory.MemoryExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private object NoOpSecurityManager : SecurityManager {
    override suspend fun authorizeAction(action: Action): Boolean = true
    override suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean = true
    override suspend fun saveSecureToken(key: String, token: String) {}
    override suspend fun getSecureToken(key: String): String? = null
}

interface ConversationManager {
    suspend fun processUserMessage(text: String): String
    fun processUserMessageStream(text: String): Flow<String> = flow { emit(processUserMessage(text)) }
    fun getMessages(): Flow<List<ChatMessage>>
    suspend fun clearSession()
}

class ConversationManagerImpl(
    private val aiProvider: AIProvider,
    private val memoryManager: MemoryManager,
    private val personalityEngine: PersonalityEngine,
    private val conversationRepository: ConversationRepository,
    private val contextBuilder: ContextBuilder,
    private val actionParser: ActionParser,
    private val actionValidator: ActionValidator,
    private val actionRouter: ActionRouter,
    private val speechNormalizer: SpeechCommandNormalizer,
    private val securityManager: SecurityManager,
    private val aliasResolver: AppAliasResolver,
    private val contactResolver: ContactResolver? = null,
    private val appRegistry: InstalledAppRegistry? = null,
    private val conversationSummaryRepository: ConversationSummaryRepository? = null,
    private val memoryExtractor: MemoryExtractor? = null,
    private val jarvisAgent: co.aura.agent.JarvisAgent? = null
) : ConversationManager {

    constructor(
        aiProvider: AIProvider,
        memoryManager: MemoryManager,
        personalityEngine: PersonalityEngine,
        conversationRepository: ConversationRepository,
        contextBuilder: ContextBuilder
    ) : this(
        aiProvider,
        memoryManager,
        personalityEngine,
        conversationRepository,
        contextBuilder,
        ActionParserImpl(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }),
        ActionValidatorImpl(),
        ActionRouterImpl(CommandBusImpl(), NoOpActionExecutor()),
        SpeechCommandNormalizer(),
        NoOpSecurityManager,
        AppAliasResolver(),
        null,
        null,
        null,
        null,
        null
    )

    constructor(
        aiProvider: AIProvider,
        memoryManager: MemoryManager,
        personalityEngine: PersonalityEngine,
        conversationRepository: ConversationRepository,
        contextBuilder: ContextBuilder,
        actionParser: ActionParser,
        actionValidator: ActionValidator,
        actionRouter: ActionRouter
    ) : this(
        aiProvider,
        memoryManager,
        personalityEngine,
        conversationRepository,
        contextBuilder,
        actionParser,
        actionValidator,
        actionRouter,
        SpeechCommandNormalizer(),
        NoOpSecurityManager,
        AppAliasResolver(),
        null,
        null,
        null,
        null,
        null
    )

    private val agent: co.aura.agent.JarvisAgent by lazy {
        jarvisAgent ?: co.aura.agent.JarvisAgentImpl(memoryManager, co.aura.agent.AgentToolRegistryImpl(), personalityEngine, speechNormalizer)
    }

    private val inMemoryHistory = mutableListOf<ChatMessage>()
    private var isHistoryLoaded = false
    private var pendingResolutionCandidates: List<InstalledApp>? = null
    private var pendingResolutionQuery: String? = null

    // Music App Discovery & Selection State
    private var pendingMusicSelectionCandidates: List<InstalledApp>? = null
    private var pendingMediaQuery: String? = null

    // Confirmation flow state — phone numbers never leave this scope
    private sealed class PendingConfirmation {
        /** Call ${contactName} at [phoneNumber hidden] */
        data class Call(val contactName: String, val contact: ContactInfo) : PendingConfirmation()
        /** SMS to ${contactName} at [phoneNumber hidden], body = message */
        data class Sms(val contactName: String, val contact: ContactInfo, val message: String) : PendingConfirmation()
        /** WhatsApp to ${contactName} at [phoneNumber hidden], body = message */
        data class WhatsApp(val contactName: String, val contact: ContactInfo, val message: String) : PendingConfirmation()
        data class DirectCall(val number: String) : PendingConfirmation()
        data class DirectSms(val number: String, val message: String) : PendingConfirmation()
        data class DirectWhatsApp(val contactName: String, val message: String) : PendingConfirmation()
        data class Email(val emailAction: SendEmailAction) : PendingConfirmation()
        data class ActionExecution(val action: Action, val description: String) : PendingConfirmation()
    }
    private var pendingConfirmation: PendingConfirmation? = null
    private var pendingConfirmationConsumed = false

    // Contact ambiguity resolution
    private var pendingContactCandidates: List<ContactInfo>? = null
    private var pendingContactAction: String? = null // "CALL" | "SMS" | "WHATSAPP"
    private var pendingContactMessage: String? = null

    // Lightweight in-memory action context
    private var lastOpenedApp: String? = null
    private var lastActionType: String? = null
    private var lastActionStatus: String? = null
    private var lastMediaTitle: String? = null
    private var lastMediaQuery: String? = null
    private var lastMediaApp: String? = null

    private fun getRecentActionContextString(): String? {
        val type = lastActionType ?: return null
        return buildString {
            if (lastOpenedApp != null) append("Last opened app: $lastOpenedApp\n")
            if (lastMediaTitle != null) append("Last played media: $lastMediaTitle\n")
            if (lastMediaQuery != null) append("Last media query: $lastMediaQuery\n")
            if (lastMediaApp != null) append("Last media app: $lastMediaApp\n")
            append("Last action: $type\n")
            if (lastActionStatus != null) append("Last action status: $lastActionStatus\n")
        }
    }

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun scheduleBackgroundMemoryAndSummarization(userText: String, assistantText: String) {
        backgroundScope.launch {
            try {
                memoryExtractor?.extractAndSaveAsync(userText, assistantText)
            } catch (e: Exception) {
                co.aura.core.logging.AuraLogger.w(co.aura.core.logging.LogCategory.AI, "Background memory extraction non-fatal error: ${e.message}")
            }
            if (inMemoryHistory.size >= 8) {
                try {
                    memoryManager.summarizeOldConversations()
                } catch (e: Exception) {
                    co.aura.core.logging.AuraLogger.w(co.aura.core.logging.LogCategory.AI, "Background summarization non-fatal error: ${e.message}")
                }
            }
        }
    }

    private fun parseAssistantDecision(rawJson: String): AssistantDecision? {
        return try {
            val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(rawJson)
            val jsonObject = jsonElement.jsonObject
            
            val type = jsonObject["type"]?.jsonPrimitive?.content ?: "NORMAL_CONVERSATION"
            val response = jsonObject["response"]?.jsonPrimitive?.content ?: ""
            
            val actions = mutableListOf<Action>()
            val actionsArray = jsonObject["actions"]?.jsonArray
            if (actionsArray != null) {
                for (element in actionsArray) {
                    val parsedAction = actionParser.parseAction(element.toString())
                    if (parsedAction != null) {
                        actions.add(parsedAction)
                    }
                }
            } else {
                // Compatibility check: if it is a single legacy action
                val parsedAction = actionParser.parseAction(rawJson)
                if (parsedAction != null) {
                    return AssistantDecision(
                        type = "ACTION",
                        response = "",
                        actions = listOf(parsedAction)
                    )
                }
            }
            
            AssistantDecision(type, response, actions)
        } catch (e: Exception) {
            null
        }
    }

    private fun finalizeResponse(decision: AssistantDecision, actionResults: List<Pair<Action, ActionResult>>): String {
        if (actionResults.isEmpty()) {
            return decision.response.ifBlank { "Action completed, sir." }
        }
        // Collect all messages; failures/ambiguities interrupt the chain
        val parts = mutableListOf<String>()
        for ((action, result) in actionResults) {
            val msg = result.message.trim()
            if (msg.isNotBlank()) {
                parts.add(msg)
            }
            when (result) {
                is ActionResult.Success -> { /* continue sequential chain */ }
                is ActionResult.Ambiguity -> break
                is ActionResult.Failure -> break
            }
        }
        val combined = parts.joinToString(" ").trim()
        return when {
            combined.isNotBlank() -> combined
            decision.response.isNotBlank() -> decision.response.trim()
            else -> "Action completed, sir."
        }
    }

    private suspend fun ensureHistoryLoaded() {
        if (isHistoryLoaded) return
        try {
            val saved = conversationRepository.getMessages(50).firstOrNull() ?: emptyList()
            inMemoryHistory.clear()
            inMemoryHistory.addAll(saved.reversed())
            isHistoryLoaded = true
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Resolves a contact by name locally. Phone numbers never leave this function scope
     * for the purpose of the Gemini prompt or conversation history.
     */
    private suspend fun resolveAndConfirmContact(contactName: String, action: String, message: String?): ActionResult {
        val resolver = contactResolver
            ?: return ActionResult.Failure("Sir, contact lookup is not available on this platform.")

        // Check if there is a saved local contact relationship alias (e.g. "mom" -> "Ananya")
        val savedAlias = securityManager.getSecureToken("contact_alias_${contactName.lowercase().trim()}")
        val targetQuery = if (!savedAlias.isNullOrBlank()) savedAlias else contactName

        var contacts = try {
            resolver.resolveContact(targetQuery)
        } catch (e: Exception) {
            co.aura.core.logging.AuraLogger.e(co.aura.core.logging.LogCategory.ACTION, "Contact resolution failed for '$targetQuery'", e)
            val errorMsg = if (e.message?.contains("permission", ignoreCase = true) == true) {
                "Sir, contact access is not enabled. Please grant permission in Android settings."
            } else {
                "Sir, I couldn't access your contacts right now."
            }
            return ActionResult.Failure(errorMsg)
        }

        // Relationship fallback (e.g. "Mom" -> check if single contact exists)
        val isRelationship = ContactMatchingUtils.relationshipAliasMap.containsKey(contactName.lowercase().trim())
        if (contacts.isEmpty() && isRelationship) {
            val allContacts = try { resolver.resolveContact("") } catch (e: Exception) { emptyList() }
            if (allContacts.size == 1) {
                val candidate = allContacts.first()
                pendingContactCandidates = listOf(candidate)
                pendingContactAction = action
                pendingContactMessage = message
                securityManager.saveSecureToken("contact_alias_${contactName.lowercase().trim()}", candidate.name)
                return ActionResult.Ambiguity(
                    message = "Sir, do you mean ${candidate.name}?",
                    candidates = emptyList()
                )
            }
        }

        return when {
            contacts.isEmpty() ->
                ActionResult.Failure("Sir, I couldn't find anyone named $contactName in your contacts.")
            contacts.size > 1 -> {
                val nameList = contacts.joinToString(", ") { it.name }
                pendingContactCandidates = contacts
                pendingContactAction = action
                pendingContactMessage = message
                val promptMsg = if (contacts.all { it.name.startsWith(contactName, ignoreCase = true) }) {
                    "Sir, I found multiple numbers for $contactName: $nameList. Which one should I use?"
                } else {
                    "Sir, I found multiple contacts matching $contactName: $nameList. Which one should I use?"
                }
                ActionResult.Ambiguity(
                    message = promptMsg,
                    candidates = emptyList()
                )
            }
            else -> {
                val contact = contacts.first()
                scheduleConfirmation(contact, action, message ?: "")
                // Return Ambiguity-style so the chain pauses — the actual execution happens after "yes"
                ActionResult.Ambiguity(
                    message = buildConfirmationPrompt(contact, action, message ?: ""),
                    candidates = emptyList()
                )
            }
        }
    }

    /**
     * Executes PLAY_MEDIA with strict source resolution order:
     * 1. Explicit source from action (e.g. "YouTube", "Spotify", "local music")
     * 2. Temporary candidate selection (from pending turn)
     * 3. Saved preferred_music_app
     * 4. Automatic discovery (if exactly 1 music app installed)
     * 5. Clarification prompt (if multiple music apps installed)
     */
    private suspend fun handlePlayMediaRequest(action: PlayMediaAction): ActionResult {
        val query = action.query
        val explicitSource = action.source?.trim()
        lastMediaQuery = query
        lastMediaTitle = query

        val registry = appRegistry
        val availableApps = registry?.getInstalledApps() ?: emptyList()

        // Debug logging (safe, no PII)
        co.aura.core.logging.AuraLogger.d(co.aura.core.logging.LogCategory.ACTION, "Requested source: '${explicitSource ?: "none"}', query: '$query'")

        // 1. EXPLICIT SOURCE SPECIFIED BY USER
        if (!explicitSource.isNullOrBlank()) {
            val resolvedApp = resolveMusicApplication(explicitSource, availableApps)
            co.aura.core.logging.AuraLogger.d(
                co.aura.core.logging.LogCategory.ACTION,
                "Resolved explicit app: ${resolvedApp?.label} (${resolvedApp?.packageName})"
            )

            if (resolvedApp != null) {
                lastMediaApp = resolvedApp.label
                return actionRouter.routeAction(PlayMediaAction(query = query, source = explicitSource, targetPackage = resolvedApp.packageName))
            } else {
                // Section 6: IF EXPLICIT SOURCE IS NOT INSTALLED -> DO NOT FALL BACK TO OTHER APPS
                co.aura.core.logging.AuraLogger.w(
                    co.aura.core.logging.LogCategory.ACTION,
                    "Explicit music app '$explicitSource' is not installed"
                )
                return ActionResult.Failure("Sorry, sir. $explicitSource isn't installed on this device.")
            }
        }

        // 2. SAVED PREFERRED MUSIC APP
        val preferredPkg = securityManager.getSecureToken("preferred_music_app")
        if (!preferredPkg.isNullOrBlank()) {
            val preferredApp = availableApps.firstOrNull { it.packageName == preferredPkg }
            if (preferredApp != null) {
                lastMediaApp = preferredApp.label
                return actionRouter.routeAction(PlayMediaAction(query = query, source = null, targetPackage = preferredApp.packageName))
            } else {
                // Preferred app no longer installed, clear token
                securityManager.saveSecureToken("preferred_music_app", "")
            }
        }

        // 3. AUTOMATIC DISCOVERY
        val musicApps = availableApps.filter { app ->
            AppCapability.MEDIA_PLAY in app.capabilities ||
            AppCapability.MEDIA_SEARCH in app.capabilities ||
            aliasResolver.getCanonicalKey(app.label) == "music" ||
            app.label.contains("music", ignoreCase = true) ||
            app.packageName.contains("music", ignoreCase = true) ||
            app.label.contains("spotify", ignoreCase = true) ||
            app.packageName.contains("spotify", ignoreCase = true) ||
            app.label.contains("youtube", ignoreCase = true) ||
            app.packageName.contains("youtube", ignoreCase = true)
        }.distinctBy { it.packageName }

        // If exactly ONE suitable music app exists -> use it automatically
        if (musicApps.size == 1) {
            val app = musicApps.first()
            lastMediaApp = app.label
            return actionRouter.routeAction(PlayMediaAction(query = query, source = null, targetPackage = app.packageName))
        }

        // If MULTIPLE suitable apps exist and no saved preference -> enter conversational selection state
        if (musicApps.size > 1) {
            pendingMusicSelectionCandidates = musicApps
            pendingMediaQuery = query
            val appNamesPrompt = musicApps.joinToString(", ") { it.label }
            return ActionResult.Ambiguity(
                message = "Sir, where would you like to listen — $appNamesPrompt?",
                candidates = emptyList()
            )
        }

        // Fallback: route generic PlayMediaAction
        return actionRouter.routeAction(action)
    }

    private fun resolveMusicApplication(source: String, availableApps: List<InstalledApp>): InstalledApp? {
        val normSource = aliasResolver.normalize(source)
        val rawLower = source.lowercase().trim()

        // 1. Exact label match or package match
        val exactMatch = availableApps.firstOrNull { app ->
            app.label.equals(source, ignoreCase = true) ||
            aliasResolver.normalize(app.label) == normSource ||
            app.packageName.equals(source, ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch

        // 2. AppAliasResolver match (e.g. "YT" -> "YouTube", "youtube" -> "YouTube")
        val aliasRes = aliasResolver.resolve(normSource, availableApps)
        if (aliasRes.matchedApp != null) return aliasRes.matchedApp

        // 3. Local music keywords ("local music", "music player", "my music", "offline music")
        if (rawLower.contains("local") || rawLower.contains("my music") || rawLower.contains("music player") || rawLower.contains("offline music")) {
            val localApp = availableApps.firstOrNull { app ->
                val lower = app.label.lowercase()
                !lower.contains("youtube") && !lower.contains("spotify") &&
                (AppCapability.MEDIA_PLAY in app.capabilities || lower.contains("music") || lower.contains("player"))
            } ?: availableApps.firstOrNull { AppCapability.MEDIA_PLAY in it.capabilities }
            if (localApp != null) return localApp
        }

        // 4. Substring / contains match
        val strongMatch = availableApps.firstOrNull { app ->
            val normLabel = aliasResolver.normalize(app.label)
            normSource.contains(normLabel) || normLabel.contains(normSource) ||
            rawLower.contains(app.label.lowercase()) || app.label.lowercase().contains(rawLower)
        }
        if (strongMatch != null) return strongMatch

        return null
    }

    private fun resolveMusicCandidateChoice(normText: String, rawLower: String, candidates: List<InstalledApp>): InstalledApp? {
        // Ordinals
        if (rawLower.contains("first") || rawLower.contains("1st") || normText == "1" || (rawLower.contains("one") && !rawLower.contains("second") && !rawLower.contains("third"))) {
            return candidates.getOrNull(0)
        }
        if (rawLower.contains("second") || rawLower.contains("2nd") || normText == "2") {
            return candidates.getOrNull(1)
        }
        if (rawLower.contains("third") || rawLower.contains("3rd") || normText == "3") {
            return candidates.getOrNull(2)
        }

        // Local music player keywords
        if (rawLower.contains("local") || rawLower.contains("my music") || rawLower.contains("music player") || rawLower.contains("player")) {
            val localApp = candidates.firstOrNull { app ->
                val lower = app.label.lowercase()
                !lower.contains("youtube") && !lower.contains("spotify")
            } ?: candidates.firstOrNull()
            if (localApp != null) return localApp
        }

        // App alias matching via AppAliasResolver
        val res = aliasResolver.resolve(normText, candidates)
        if (res.matchedApp != null) return res.matchedApp

        // Label / Package substring matching
        return candidates.firstOrNull { app ->
            val normLabel = aliasResolver.normalize(app.label)
            normText.contains(normLabel) || normLabel.contains(normText) ||
            rawLower.contains(app.label.lowercase())
        }
    }

    /** Stores a pending confirmation. Phone numbers stay in-memory only. */
    private fun scheduleConfirmation(contact: ContactInfo, action: String, message: String): String {
        pendingConfirmation = when (action) {
            "CALL"     -> PendingConfirmation.Call(contact.name, contact)
            "SMS"      -> PendingConfirmation.Sms(contact.name, contact, message)
            "WHATSAPP" -> PendingConfirmation.WhatsApp(contact.name, contact, message)
            else       -> return "Sir, that action type isn't supported."
        }
        return buildConfirmationPrompt(contact, action, message)
    }

    private fun buildConfirmationPrompt(contact: ContactInfo, action: String, message: String): String {
        // NOTE: phone number is intentionally excluded from the prompt
        return when (action) {
            "CALL"     -> "Sir, shall I call ${contact.name}? Say yes to confirm or no to cancel."
            "SMS"      -> "Sir, shall I send \"$message\" to ${contact.name}? Say yes to confirm or no to cancel."
            "WHATSAPP" -> "Sir, shall I send \"$message\" to ${contact.name} on WhatsApp? Say yes to confirm or no to cancel."
            else       -> "Sir, shall I proceed? Say yes to confirm or no to cancel."
        }
    }

    private fun mapLocalCommandToAction(command: LocalCommand): Action? {
        return when (command) {
            is LocalCommand.LaunchApp -> OpenAppAction(command.appLabel ?: command.appTarget)
            is LocalCommand.OpenSettings -> if (command.panel == SettingsPanel.MAIN) OpenAppAction("Settings") else OpenSettingsAction(command.panel)
            is LocalCommand.OpenUrl -> OpenUrlAction(command.url)
            is LocalCommand.WebSearch -> WebSearchAction(command.query)
            is LocalCommand.DeviceInfo -> {
                when (command.type) {
                    DeviceInfoType.BATTERY -> GetBatteryAction()
                    DeviceInfoType.TIME -> GetCurrentTimeAction()
                    else -> GetDeviceInfoAction(command.type)
                }
            }
            is LocalCommand.VolumeControl -> {
                when {
                    command.showPanel -> ShowVolumePanelAction()
                    command.isMute -> MuteVolumeAction()
                    command.level != null -> SetVolumeAction(command.level)
                    command.isIncrease == true -> VolumeUpAction()
                    command.isIncrease == false -> VolumeDownAction()
                    else -> ShowVolumePanelAction()
                }
            }
            is LocalCommand.TorchControl -> ToggleFlashlightAction(command.enabled)
            is LocalCommand.MediaControl -> {
                when (command.command) {
                    "pause" -> PauseMediaAction()
                    "resume" -> ResumeMediaAction()
                    "stop" -> StopMediaAction()
                    "next", "skip" -> SkipMediaAction()
                    "previous" -> PreviousMediaAction()
                    else -> PlayMediaAction(command.query ?: "", command.source)
                }
            }
            is LocalCommand.PhoneCall -> {
                if (command.contactName != null) CallContactAction(command.contactName)
                else if (command.number != null) CallAction(command.number)
                else null
            }
            is LocalCommand.SmsMessage -> {
                if (command.contactName != null) SendSmsAction(command.contactName, command.message)
                else if (command.number != null) SmsAction(command.number, command.message)
                else null
            }
            is LocalCommand.WhatsAppMessage -> SendWhatsAppAction(command.contactName, command.message)
            is LocalCommand.SendEmail -> SendEmailAction(command.recipient, command.subject, command.body)
            is LocalCommand.AlarmControl -> SetAlarmAction(command.hour, command.minute, command.label)
            is LocalCommand.TimerControl -> SetTimerAction(command.durationSeconds, command.label)
            is LocalCommand.NoteManage -> ManageNoteAction(command.operation, command.content, command.noteId)
            is LocalCommand.ReminderManage -> ManageReminderAction(command.operation, command.title, command.timestampMillis)
            is LocalCommand.ClipboardAction -> CopyToClipboardAction(command.text)
            is LocalCommand.ShareAction -> ShareTextAction(command.text)
            is LocalCommand.CameraCapture -> CameraCaptureAction(command.autoCapture)
            is LocalCommand.Unsupported -> command.fallbackCommand?.let { mapLocalCommandToAction(it) }
            else -> null
        }
    }

    private fun scheduleConfirmationForCommand(command: LocalCommand, prompt: String): String? {
        return when (command) {
            is LocalCommand.PhoneCall -> {
                if (command.number != null) {
                    pendingConfirmation = PendingConfirmation.DirectCall(command.number)
                    prompt.ifBlank { "Sir, shall I call ${command.number}? Say yes to confirm or no to cancel." }
                } else null
            }
            is LocalCommand.SmsMessage -> {
                if (command.number != null) {
                    pendingConfirmation = PendingConfirmation.DirectSms(command.number, command.message)
                    prompt.ifBlank { "Sir, shall I send \"${command.message}\" to ${command.number}? Say yes to confirm or no to cancel." }
                } else null
            }
            is LocalCommand.WhatsAppMessage -> null
            is LocalCommand.SendEmail -> {
                val emailAction = SendEmailAction(command.recipient, command.subject, command.body)
                pendingConfirmation = PendingConfirmation.Email(emailAction)
                prompt.ifBlank { "Sir, shall I prepare an email to ${command.recipient ?: "the recipient"}? Say yes to confirm or no to cancel." }
            }
            is LocalCommand.NoteManage -> {
                if (command.operation == NoteOperationType.DELETE) {
                    val action = ManageNoteAction(command.operation, command.content, command.noteId)
                    pendingConfirmation = PendingConfirmation.ActionExecution(action, "deletion of note \"${command.content}\"")
                    prompt.ifBlank { "Sir, are you sure you want to delete note \"${command.content}\"? Say yes to confirm or no to cancel." }
                } else null
            }
            else -> {
                val action = mapLocalCommandToAction(command)
                if (action != null) {
                    pendingConfirmation = PendingConfirmation.ActionExecution(action, "action")
                    prompt.ifBlank { "Sir, shall I proceed with this action? Say yes to confirm or no to cancel." }
                } else null
            }
        }
    }

    private suspend fun executeLocalCommand(command: LocalCommand, rawText: String): String {
        return when (command) {
            is LocalCommand.WakeAcknowledgement -> LocalResponseProvider.wakeGreeting()
            is LocalCommand.VoiceStopOrCancel -> LocalResponseProvider.voiceStop()
            is LocalCommand.MemoryManage -> {
                if (command.isRemember) {
                    val score = memoryManager.scoreMemoryImportance(command.content)
                    val success = memoryManager.storeMemory(command.content, "PREFERENCE", score)
                    if (success) "I'll remember that, sir." else "Sorry, sir. I couldn't save that memory."
                } else if (command.isForget) {
                    val allMemories = memoryManager.searchMemories("")
                    val matches = allMemories.filter { it.content.contains(command.content, ignoreCase = true) }
                    if (matches.isNotEmpty()) {
                        memoryManager.forgetMemory(matches.first().id)
                        "I have forgotten: \"${matches.first().content}\", sir."
                    } else {
                        "I couldn't find any memory matching: \"${command.content}\", sir."
                    }
                } else {
                    val allMemories = memoryManager.searchMemories("")
                    if (allMemories.isEmpty()) {
                        "I don't have any saved memories about you yet, sir."
                    } else {
                        "Here is what I remember about you, sir:\n" +
                                allMemories.take(5).map { "- ${it.content}" }.joinToString("\n")
                    }
                }
            }
            else -> {
                val action = mapLocalCommandToAction(command)
                    ?: return "Sir, that operation is not currently supported."

                val result: ActionResult = when (action) {
                    is CallContactAction -> resolveAndConfirmContact(action.contactName, "CALL", null)
                    is SendSmsAction -> resolveAndConfirmContact(action.contactName, "SMS", action.message)
                    is SendWhatsAppAction -> resolveAndConfirmContact(action.contactName, "WHATSAPP", action.message)
                    is PlayMediaAction -> handlePlayMediaRequest(action)
                    else -> actionRouter.routeAction(action)
                }

                lastActionType = action.actionType
                when {
                    action is OpenAppAction && result is ActionResult.Success -> {
                        lastOpenedApp = action.appName
                        lastActionStatus = "Success"
                    }
                    action is OpenAppAction && result is ActionResult.Failure -> lastActionStatus = "Failure"
                    else -> lastActionStatus = if (result is ActionResult.Success) "Success" else "Failure"
                }

                if (result is ActionResult.Ambiguity && action is OpenAppAction) {
                    pendingResolutionCandidates = result.candidates
                    pendingResolutionQuery = aliasResolver.getCanonicalKey(action.appName) ?: aliasResolver.normalize(action.appName)
                }

                when (result) {
                    is ActionResult.Success -> result.message.ifBlank { "Action completed, sir." }
                    is ActionResult.Failure -> result.message.ifBlank { "Sir, the action could not be completed." }
                    is ActionResult.Ambiguity -> result.message.ifBlank { "Sir, please specify which one you prefer." }
                }
            }
        }
    }


    override suspend fun processUserMessage(text: String): String {
        ensureHistoryLoaded()

        val normalizedActionText = speechNormalizer.normalize(text)
        val lowerInput = normalizedActionText.lowercase().trim()

        // ── 0a. Confirmation flow — yes/no executes exactly once ──────────────────
        val confirmation = pendingConfirmation
        if (confirmation != null && !pendingConfirmationConsumed) {
            val isYes = lowerInput == "yes" || lowerInput.contains("yes") || lowerInput.contains("confirm") || lowerInput.contains("do it") || lowerInput.contains("go ahead")
            val isNo  = lowerInput == "no"  || lowerInput.contains("no")  || lowerInput.contains("cancel") || lowerInput.contains("stop") || lowerInput.contains("abort")
            if (isYes || isNo) {
                pendingConfirmation = null
                pendingConfirmationConsumed = true // prevent re-entry
                val responseMsg = if (isYes) {
                    val result = when (confirmation) {
                        is PendingConfirmation.Call ->
                            actionRouter.routeAction(co.aura.actions.CallAction(confirmation.contact.phoneNumber))
                        is PendingConfirmation.Sms ->
                            actionRouter.routeAction(co.aura.actions.SmsAction(confirmation.contact.phoneNumber, confirmation.message))
                        is PendingConfirmation.WhatsApp ->
                            actionRouter.routeAction(co.aura.actions.SendWhatsAppAction(confirmation.contactName, confirmation.message))
                        is PendingConfirmation.DirectCall ->
                            actionRouter.routeAction(co.aura.actions.CallAction(confirmation.number))
                        is PendingConfirmation.DirectSms ->
                            actionRouter.routeAction(co.aura.actions.SmsAction(confirmation.number, confirmation.message))
                        is PendingConfirmation.DirectWhatsApp ->
                            actionRouter.routeAction(co.aura.actions.SendWhatsAppAction(confirmation.contactName, confirmation.message))
                        is PendingConfirmation.Email ->
                            actionRouter.routeAction(confirmation.emailAction)
                        is PendingConfirmation.ActionExecution ->
                            actionRouter.routeAction(confirmation.action)
                    }
                    lastActionStatus = if (result is ActionResult.Success) "Success" else "Failure"
                    val msg = when (result) {
                        is ActionResult.Success -> if (result.message.isNotBlank()) result.message else "Action completed, sir."
                        is ActionResult.Failure -> if (result.message.isNotBlank()) result.message else "Sir, the action could not be completed."
                        is ActionResult.Ambiguity -> result.message
                    }
                    msg.ifBlank { "Action completed, sir." }
                } else {
                    lastActionStatus = "Cancelled"
                    val desc = when (confirmation) {
                        is PendingConfirmation.Call -> "action for ${confirmation.contactName}"
                        is PendingConfirmation.Sms -> "message for ${confirmation.contactName}"
                        is PendingConfirmation.WhatsApp -> "WhatsApp message for ${confirmation.contactName}"
                        is PendingConfirmation.DirectCall -> "call for ${confirmation.number}"
                        is PendingConfirmation.DirectSms -> "SMS for ${confirmation.number}"
                        is PendingConfirmation.DirectWhatsApp -> "WhatsApp message for ${confirmation.contactName}"
                        is PendingConfirmation.Email -> "email to ${confirmation.emailAction.recipient ?: "recipient"}"
                        is PendingConfirmation.ActionExecution -> confirmation.description
                    }
                    "Understood, sir. The $desc has been cancelled."
                }
                pendingConfirmationConsumed = false // reset for next confirmation
                saveMessageAndHistory(text, responseMsg)
                return responseMsg
            }
        } else if (pendingConfirmationConsumed) {
            pendingConfirmationConsumed = false
        }

        // ── 0b. Contact ambiguity resolution ─────────────────────────────────────
        val contactCandidates = pendingContactCandidates
        if (contactCandidates != null) {
            pendingContactCandidates = null
            val action = pendingContactAction
            val message = pendingContactMessage
            pendingContactAction = null
            pendingContactMessage = null

            val normText = speechNormalizer.normalize(text)
            val selectedContact = when {
                lowerInput.contains("first") || lowerInput.contains("1st") || normText == "1" -> contactCandidates.getOrNull(0)
                lowerInput.contains("second") || lowerInput.contains("2nd") || normText == "2" -> contactCandidates.getOrNull(1)
                lowerInput.contains("third") || lowerInput.contains("3rd") || normText == "3" -> contactCandidates.getOrNull(2)
                else -> contactCandidates.firstOrNull { candidate ->
                    val normName = speechNormalizer.normalize(candidate.name)
                    lowerInput.contains(candidate.name.lowercase()) ||
                    candidate.name.lowercase().contains(lowerInput) ||
                    normText.contains(normName) || normName.contains(normText) ||
                    ContactMatchingUtils.isFuzzyMatch(lowerInput, candidate.name)
                }
            }
            if (selectedContact != null && action != null) {
                val responseMsg = scheduleConfirmation(selectedContact, action, message ?: "")
                saveMessageAndHistory(text, responseMsg)
                return responseMsg
            }
        }

        // ── 0c. Music player selection resolution ─────────────────────────────────
        val musicCandidates = pendingMusicSelectionCandidates
        if (musicCandidates != null) {
            pendingMusicSelectionCandidates = null
            val savedQuery = pendingMediaQuery ?: ""
            pendingMediaQuery = null

            val rawLower = text.lowercase().trim()
            val normText = speechNormalizer.normalize(text)

            val isAlways = rawLower.contains("always") || rawLower.contains("forever") || rawLower.contains("default")
            val isThisTime = rawLower.contains("this time") || rawLower.contains("just for now") || rawLower.contains("only now")

            val selectedApp = resolveMusicCandidateChoice(normText, rawLower, musicCandidates)
            if (selectedApp != null) {
                if (isAlways || (!isThisTime && securityManager.getSecureToken("preferred_music_app").isNullOrBlank())) {
                    securityManager.saveSecureToken("preferred_music_app", selectedApp.packageName)
                }

                lastMediaApp = selectedApp.label
                lastMediaQuery = savedQuery
                val actionResult = actionRouter.routeAction(PlayMediaAction(query = savedQuery, source = null, targetPackage = selectedApp.packageName))
                lastActionType = "PLAY_MEDIA"
                lastActionStatus = if (actionResult is ActionResult.Success) "Success" else "Failure"

                val responseMsg = actionResult.message
                saveMessageAndHistory(text, responseMsg)
                return responseMsg
            }
        }

        // ── 0d. App-ambiguity resolution (existing) ────────────────────────────
        val candidates = pendingResolutionCandidates
        if (candidates != null) {
            pendingResolutionCandidates = null
            val category = pendingResolutionQuery
            pendingResolutionQuery = null

            val normalizedReply = speechNormalizer.normalize(text)
            val selectedApp = candidates.firstOrNull { candidate ->
                val normLabel = speechNormalizer.normalize(candidate.label)
                normalizedReply.contains(normLabel) || normLabel.contains(normalizedReply)
            }
            if (selectedApp != null) {
                if (category != null) {
                    securityManager.saveSecureToken("preferred_app_$category", selectedApp.packageName)
                }
                val actionResult = actionRouter.routeAction(OpenAppAction(selectedApp.packageName))
                lastOpenedApp = selectedApp.label
                lastActionType = "OPEN_APP"
                lastActionStatus = "Success"
                val responseMsg = "Opening ${selectedApp.label}, sir."
                saveMessageAndHistory(text, responseMsg)
                return responseMsg
            }
        }

        // ── 0e. Local-First Command Routing (Zero Token Guarantee) ───────────────
        var promptForGemini = text
        when (val localRouting = LocalCommandRouter.route(text)) {
            is CommandRoutingResult.LocalDirectResponse -> {
                TokenUsageTracker.recordLocalCommandSaved()
                saveMessageAndHistory(text, localRouting.responseText)
                scheduleBackgroundMemoryAndSummarization(text, localRouting.responseText)
                return localRouting.responseText
            }
            is CommandRoutingResult.ClarificationNeeded -> {
                TokenUsageTracker.recordLocalCommandSaved()
                saveMessageAndHistory(text, localRouting.prompt)
                return localRouting.prompt
            }
            is CommandRoutingResult.UnsupportedOperation -> {
                TokenUsageTracker.recordLocalCommandSaved()
                var fullResponse = localRouting.explanation
                if (localRouting.fallbackCommand != null) {
                    val fallbackMsg = executeLocalCommand(localRouting.fallbackCommand, text)
                    if (fallbackMsg.isNotBlank()) {
                        fullResponse = "$fullResponse $fallbackMsg"
                    }
                }
                saveMessageAndHistory(text, fullResponse)
                return fullResponse
            }
            is CommandRoutingResult.LocalExecution -> {
                TokenUsageTracker.recordLocalCommandSaved()
                if (localRouting.requiresConfirmation) {
                    val prompt = scheduleConfirmationForCommand(localRouting.command, localRouting.confirmationPrompt)
                    if (prompt != null) {
                        saveMessageAndHistory(text, prompt)
                        return prompt
                    }
                }
                val localResponse = executeLocalCommand(localRouting.command, text)
                saveMessageAndHistory(text, localResponse)
                scheduleBackgroundMemoryAndSummarization(text, localResponse)
                return localResponse
            }
            is CommandRoutingResult.RequiresAi -> {
                if (localRouting.preExecutionCommand != null) {
                    executeLocalCommand(localRouting.preExecutionCommand, text)
                }
                promptForGemini = localRouting.promptForGemini
            }
        }

        // Extract Dynamic ConversationMode from settings
        val modeStr = securityManager.getSecureToken("conversation_mode") ?: "HYBRID"
        val conversationMode = try {
            ConversationMode.valueOf(modeStr)
        } catch (e: Exception) {
            ConversationMode.HYBRID
        }

        val conversationSummary = try {
            conversationSummaryRepository?.getSummary("session_default")
        } catch (e: Exception) {
            null
        }

        val planResult = agent.plan(
            userText = promptForGemini,
            conversationMode = conversationMode,
            inMemoryHistory = inMemoryHistory,
            recentActionContext = getRecentActionContextString(),
            conversationSummary = conversationSummary
        )

        val responseText = when (planResult) {
            is co.aura.agent.AgentPlanResult.DirectResponse -> {
                val action = planResult.action
                val responseMsg = if (action != null) {
                    if (actionValidator.validateAction(action)) {
                        val result = actionRouter.routeAction(action)
                        lastActionType = action.actionType
                        when {
                            action is OpenAppAction && result is ActionResult.Success -> {
                                lastOpenedApp = action.appName
                                lastActionStatus = "Success"
                            }
                            action is OpenAppAction && result is ActionResult.Failure -> lastActionStatus = "Failure"
                            else -> lastActionStatus = if (result is ActionResult.Success) "Success" else "Failure"
                        }
                        val msg = when (result) {
                            is ActionResult.Success -> if (result.message.isNotBlank()) result.message else planResult.responseText
                            is ActionResult.Failure -> if (result.message.isNotBlank()) result.message else "Sir, the action could not be completed."
                            is ActionResult.Ambiguity -> if (result.message.isNotBlank()) result.message else "Sir, please specify which one you prefer."
                        }
                        msg.ifBlank { "Action completed, sir." }
                    } else {
                        "Sir, the requested action is invalid."
                    }
                } else {
                    planResult.responseText.ifBlank { "Understood, sir." }
                }
                saveMessageAndHistory(text, responseMsg)
                scheduleBackgroundMemoryAndSummarization(text, responseMsg)
                return responseMsg
            }
            is co.aura.agent.AgentPlanResult.RequiresAi -> {
                val userMsg = ChatMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    sessionId = "session_default",
                    sender = MessageSender.USER,
                    content = text,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.SENT
                )
                inMemoryHistory.add(userMsg)
                try {
                    conversationRepository.saveMessage(userMsg)
                } catch (e: Exception) {}

                val contextPrompt = contextBuilder.buildContext(planResult.agentContext)

                try {
                    aiProvider.generateResponse(contextPrompt)
                } catch (e: AuraError) {
                    throw e
                } catch (e: Exception) {
                    co.aura.core.logging.AuraLogger.e(co.aura.core.logging.LogCategory.AI, "AI request failed", e)
                    throw AuraError.AIRequestError("Sorry, sir. I'm having trouble reaching my AI service at the moment.")
                }
            }
        }

        val cleanedResponse = responseText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        co.aura.core.logging.AuraLogger.i(co.aura.core.logging.LogCategory.ACTION, "[DIAGNOSTIC] User Speech / Request: '$text'")
        co.aura.core.logging.AuraLogger.i(co.aura.core.logging.LogCategory.ACTION, "[DIAGNOSTIC] Gemini Response: '$cleanedResponse'")

        val decision = if (cleanedResponse.startsWith("{") && cleanedResponse.endsWith("}")) {
            parseAssistantDecision(cleanedResponse)
        } else {
            null
        } ?: AssistantDecision(
            type = "NORMAL_CONVERSATION",
            response = responseText
        )

        val actionResults = mutableListOf<Pair<Action, ActionResult>>()
        for (action in decision.actions) {
            co.aura.core.logging.AuraLogger.i(co.aura.core.logging.LogCategory.ACTION, "[DIAGNOSTIC] Action Parsed: ${action.actionType}")
            if (action is OpenAppAction) {
                co.aura.core.logging.AuraLogger.i(co.aura.core.logging.LogCategory.ACTION, "[DIAGNOSTIC] App Name: '${action.appName}'")
            }
            // NOTE: CallContactAction / SendSmsAction / SendWhatsAppAction are handled locally.
            // Phone numbers are resolved here and NEVER forwarded to Gemini or logs.

            if (!actionValidator.validateAction(action)) {
                co.aura.core.logging.AuraLogger.w(co.aura.core.logging.LogCategory.ACTION, "[DIAGNOSTIC] Action validation failed: ${action.actionType}")
                actionResults.add(action to ActionResult.Failure("Sir, the requested action is invalid."))
                break // abort sequential chain on validation failure
            }

            val actionResult: ActionResult = when (action) {
                is CallContactAction -> resolveAndConfirmContact(action.contactName, "CALL", null)
                is SendSmsAction     -> resolveAndConfirmContact(action.contactName, "SMS", action.message)
                is SendWhatsAppAction -> resolveAndConfirmContact(action.contactName, "WHATSAPP", action.message)
                is PlayMediaAction   -> handlePlayMediaRequest(action)
                else -> actionRouter.routeAction(action)
            }

            actionResults.add(action to actionResult)

            // Track recent action context
            lastActionType = action.actionType
            when {
                action is OpenAppAction && actionResult is ActionResult.Success -> {
                    lastOpenedApp = action.appName
                    lastActionStatus = "Success"
                }
                action is OpenAppAction && actionResult is ActionResult.Failure -> lastActionStatus = "Failure"
                else -> lastActionStatus = if (actionResult is ActionResult.Success) "Success" else "Failure"
            }

            // On ambiguity — store pending state and stop chain
            if (actionResult is ActionResult.Ambiguity) {
                if (action is OpenAppAction) {
                    pendingResolutionCandidates = actionResult.candidates
                    pendingResolutionQuery = aliasResolver.getCanonicalKey(action.appName) ?: aliasResolver.normalize(action.appName)
                }
                break
            }
            // On failure — abort multi-action chain
            if (actionResult is ActionResult.Failure) {
                break
            }
        }

        val finalResponseText = if (actionResults.isNotEmpty()) {
            finalizeResponse(decision, actionResults)
        } else {
            decision.response
        }

        val assistantMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis() + 1}",
            sessionId = "session_default",
            sender = MessageSender.ASSISTANT,
            content = finalResponseText,
            timestamp = System.currentTimeMillis() + 1,
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(assistantMsg)
        try {
            conversationRepository.saveMessage(assistantMsg)
        } catch (e: Exception) {
            // Ignore DB cache errors
        }

        // Asynchronous background extraction and summarization
        scheduleBackgroundMemoryAndSummarization(text, finalResponseText)

        return finalResponseText
    }

    private suspend fun saveMessageAndHistory(userText: String, assistantText: String) {
        val userMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis()}",
            sessionId = "session_default",
            sender = MessageSender.USER,
            content = userText,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(userMsg)
        try {
            conversationRepository.saveMessage(userMsg)
        } catch (e: Exception) {}

        val assistantMsg = ChatMessage(
            id = "msg_${System.currentTimeMillis() + 1}",
            sessionId = "session_default",
            sender = MessageSender.ASSISTANT,
            content = assistantText,
            timestamp = System.currentTimeMillis() + 1,
            status = MessageStatus.SENT
        )
        inMemoryHistory.add(assistantMsg)
        try {
            conversationRepository.saveMessage(assistantMsg)
        } catch (e: Exception) {}
    }

    override fun getMessages(): Flow<List<ChatMessage>> {
        return conversationRepository.getMessages(50).map { list ->
            list.sortedBy { it.timestamp }
        }
    }

    override suspend fun clearSession() {
        inMemoryHistory.clear()
        try {
            conversationRepository.clearHistory()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            conversationSummaryRepository?.clearSummary("session_default")
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun processUserMessageStream(text: String): Flow<String> = flow {
        ensureHistoryLoaded()

        val normalizedActionText = speechNormalizer.normalize(text)
        val lowerInput = normalizedActionText.lowercase().trim()

        // ── 0a. Confirmation flow — yes/no executes exactly once ──────────────────
        val confirmation = pendingConfirmation
        if (confirmation != null && !pendingConfirmationConsumed) {
            val isYes = lowerInput == "yes" || lowerInput.contains("yes") || lowerInput.contains("confirm") || lowerInput.contains("do it") || lowerInput.contains("go ahead")
            val isNo  = lowerInput == "no"  || lowerInput.contains("no")  || lowerInput.contains("cancel") || lowerInput.contains("stop") || lowerInput.contains("abort")
            if (isYes || isNo) {
                pendingConfirmation = null
                pendingConfirmationConsumed = true
                val responseMsg = if (isYes) {
                    val result = when (confirmation) {
                        is PendingConfirmation.Call ->
                            actionRouter.routeAction(co.aura.actions.CallAction(confirmation.contact.phoneNumber))
                        is PendingConfirmation.Sms ->
                            actionRouter.routeAction(co.aura.actions.SmsAction(confirmation.contact.phoneNumber, confirmation.message))
                        is PendingConfirmation.WhatsApp ->
                            actionRouter.routeAction(co.aura.actions.SendWhatsAppAction(confirmation.contactName, confirmation.message))
                        is PendingConfirmation.DirectCall ->
                            actionRouter.routeAction(co.aura.actions.CallAction(confirmation.number))
                        is PendingConfirmation.DirectSms ->
                            actionRouter.routeAction(co.aura.actions.SmsAction(confirmation.number, confirmation.message))
                        is PendingConfirmation.DirectWhatsApp ->
                            actionRouter.routeAction(co.aura.actions.SendWhatsAppAction(confirmation.contactName, confirmation.message))
                        is PendingConfirmation.Email ->
                            actionRouter.routeAction(confirmation.emailAction)
                        is PendingConfirmation.ActionExecution ->
                            actionRouter.routeAction(confirmation.action)
                    }
                    lastActionStatus = if (result is ActionResult.Success) "Success" else "Failure"
                    val msg = when (result) {
                        is ActionResult.Success -> if (result.message.isNotBlank()) result.message else "Action completed, sir."
                        is ActionResult.Failure -> if (result.message.isNotBlank()) result.message else "Sir, the action could not be completed."
                        is ActionResult.Ambiguity -> result.message
                    }
                    msg.ifBlank { "Action completed, sir." }
                } else {
                    lastActionStatus = "Cancelled"
                    val desc = when (confirmation) {
                        is PendingConfirmation.Call -> "action for ${confirmation.contactName}"
                        is PendingConfirmation.Sms -> "message for ${confirmation.contactName}"
                        is PendingConfirmation.WhatsApp -> "WhatsApp message for ${confirmation.contactName}"
                        is PendingConfirmation.DirectCall -> "call for ${confirmation.number}"
                        is PendingConfirmation.DirectSms -> "SMS for ${confirmation.number}"
                        is PendingConfirmation.DirectWhatsApp -> "WhatsApp message for ${confirmation.contactName}"
                        is PendingConfirmation.Email -> "email to ${confirmation.emailAction.recipient ?: "recipient"}"
                        is PendingConfirmation.ActionExecution -> confirmation.description
                    }
                    "Understood, sir. The $desc has been cancelled."
                }
                pendingConfirmationConsumed = false
                saveMessageAndHistory(text, responseMsg)
                emit(responseMsg)
                return@flow
            }
        } else if (pendingConfirmationConsumed) {
            pendingConfirmationConsumed = false
        }

        // ── 0b. Contact ambiguity resolution ─────────────────────────────────────
        val contactCandidates = pendingContactCandidates
        if (contactCandidates != null) {
            pendingContactCandidates = null
            val action = pendingContactAction
            val message = pendingContactMessage
            pendingContactAction = null
            pendingContactMessage = null

            val normText = speechNormalizer.normalize(text)
            val selectedContact = when {
                lowerInput.contains("first") || lowerInput.contains("1st") || normText == "1" -> contactCandidates.getOrNull(0)
                lowerInput.contains("second") || lowerInput.contains("2nd") || normText == "2" -> contactCandidates.getOrNull(1)
                lowerInput.contains("third") || lowerInput.contains("3rd") || normText == "3" -> contactCandidates.getOrNull(2)
                else -> contactCandidates.firstOrNull { candidate ->
                    val normName = speechNormalizer.normalize(candidate.name)
                    lowerInput.contains(candidate.name.lowercase()) ||
                    candidate.name.lowercase().contains(lowerInput) ||
                    normText.contains(normName) || normName.contains(normText) ||
                    ContactMatchingUtils.isFuzzyMatch(lowerInput, candidate.name)
                }
            }
            if (selectedContact != null && action != null) {
                val responseMsg = scheduleConfirmation(selectedContact, action, message ?: "")
                saveMessageAndHistory(text, responseMsg)
                emit(responseMsg)
                return@flow
            }
        }

        // ── 0c. Music player selection resolution ─────────────────────────────────
        val musicCandidates = pendingMusicSelectionCandidates
        if (musicCandidates != null) {
            pendingMusicSelectionCandidates = null
            val savedQuery = pendingMediaQuery ?: ""
            pendingMediaQuery = null

            val rawLower = text.lowercase().trim()
            val normText = speechNormalizer.normalize(text)

            val isAlways = rawLower.contains("always") || rawLower.contains("forever") || rawLower.contains("default")
            val isThisTime = rawLower.contains("this time") || rawLower.contains("just for now") || rawLower.contains("only now")

            val selectedApp = resolveMusicCandidateChoice(normText, rawLower, musicCandidates)
            if (selectedApp != null) {
                if (isAlways || (!isThisTime && securityManager.getSecureToken("preferred_music_app").isNullOrBlank())) {
                    securityManager.saveSecureToken("preferred_music_app", selectedApp.packageName)
                }

                lastMediaApp = selectedApp.label
                lastMediaQuery = savedQuery
                val actionResult = actionRouter.routeAction(PlayMediaAction(query = savedQuery, source = null, targetPackage = selectedApp.packageName))
                lastActionType = "PLAY_MEDIA"
                lastActionStatus = if (actionResult is ActionResult.Success) "Success" else "Failure"

                val responseMsg = actionResult.message
                saveMessageAndHistory(text, responseMsg)
                emit(responseMsg)
                return@flow
            }
        }

        // ── 0d. App-ambiguity resolution ──────────────────────────────────────────
        val candidates = pendingResolutionCandidates
        if (candidates != null) {
            pendingResolutionCandidates = null
            val category = pendingResolutionQuery
            pendingResolutionQuery = null

            val normalizedReply = speechNormalizer.normalize(text)
            val selectedApp = candidates.firstOrNull { candidate ->
                val normLabel = speechNormalizer.normalize(candidate.label)
                normalizedReply.contains(normLabel) || normLabel.contains(normalizedReply)
            }
            if (selectedApp != null) {
                if (category != null) {
                    securityManager.saveSecureToken("preferred_app_$category", selectedApp.packageName)
                }
                val actionResult = actionRouter.routeAction(OpenAppAction(selectedApp.packageName))
                lastOpenedApp = selectedApp.label
                lastActionType = "OPEN_APP"
                lastActionStatus = "Success"
                val responseMsg = "Opening ${selectedApp.label}, sir."
                saveMessageAndHistory(text, responseMsg)
                emit(responseMsg)
                return@flow
            }
        }

        // ── 0e. Local-First Command Routing (Zero Token Guarantee) ───────────────
        var promptForGemini = text
        when (val localRouting = LocalCommandRouter.route(text)) {
            is CommandRoutingResult.LocalDirectResponse -> {
                TokenUsageTracker.recordLocalCommandSaved()
                saveMessageAndHistory(text, localRouting.responseText)
                scheduleBackgroundMemoryAndSummarization(text, localRouting.responseText)
                emit(localRouting.responseText)
                return@flow
            }
            is CommandRoutingResult.ClarificationNeeded -> {
                TokenUsageTracker.recordLocalCommandSaved()
                saveMessageAndHistory(text, localRouting.prompt)
                emit(localRouting.prompt)
                return@flow
            }
            is CommandRoutingResult.UnsupportedOperation -> {
                TokenUsageTracker.recordLocalCommandSaved()
                var fullResponse = localRouting.explanation
                if (localRouting.fallbackCommand != null) {
                    val fallbackMsg = executeLocalCommand(localRouting.fallbackCommand, text)
                    if (fallbackMsg.isNotBlank()) {
                        fullResponse = "$fullResponse $fallbackMsg"
                    }
                }
                saveMessageAndHistory(text, fullResponse)
                emit(fullResponse)
                return@flow
            }
            is CommandRoutingResult.LocalExecution -> {
                TokenUsageTracker.recordLocalCommandSaved()
                if (localRouting.requiresConfirmation) {
                    val prompt = scheduleConfirmationForCommand(localRouting.command, localRouting.confirmationPrompt)
                    if (prompt != null) {
                        saveMessageAndHistory(text, prompt)
                        emit(prompt)
                        return@flow
                    }
                }
                val localResponse = executeLocalCommand(localRouting.command, text)
                saveMessageAndHistory(text, localResponse)
                scheduleBackgroundMemoryAndSummarization(text, localResponse)
                emit(localResponse)
                return@flow
            }
            is CommandRoutingResult.RequiresAi -> {
                if (localRouting.preExecutionCommand != null) {
                    executeLocalCommand(localRouting.preExecutionCommand, text)
                }
                promptForGemini = localRouting.promptForGemini
            }
        }

        val modeStr = securityManager.getSecureToken("conversation_mode") ?: "HYBRID"
        val conversationMode = try {
            ConversationMode.valueOf(modeStr)
        } catch (e: Exception) {
            ConversationMode.HYBRID
        }

        val conversationSummary = try {
            conversationSummaryRepository?.getSummary("session_default")
        } catch (e: Exception) {
            null
        }

        val planResult = agent.plan(
            userText = promptForGemini,
            conversationMode = conversationMode,
            inMemoryHistory = inMemoryHistory,
            recentActionContext = getRecentActionContextString(),
            conversationSummary = conversationSummary
        )

        val contextPrompt = when (planResult) {
            is co.aura.agent.AgentPlanResult.DirectResponse -> {
                val action = planResult.action
                val responseMsg = if (action != null) {
                    if (actionValidator.validateAction(action)) {
                        val result = actionRouter.routeAction(action)
                        lastActionType = action.actionType
                        when {
                            action is OpenAppAction && result is ActionResult.Success -> {
                                lastOpenedApp = action.appName
                                lastActionStatus = "Success"
                            }
                            action is OpenAppAction && result is ActionResult.Failure -> lastActionStatus = "Failure"
                            else -> lastActionStatus = if (result is ActionResult.Success) "Success" else "Failure"
                        }
                        val msg = when (result) {
                            is ActionResult.Success -> if (result.message.isNotBlank()) result.message else planResult.responseText
                            is ActionResult.Failure -> if (result.message.isNotBlank()) result.message else "Sir, the action could not be completed."
                            is ActionResult.Ambiguity -> if (result.message.isNotBlank()) result.message else "Sir, please specify which one you prefer."
                        }
                        msg.ifBlank { "Action completed, sir." }
                    } else {
                        "Sir, the requested action is invalid."
                    }
                } else {
                    planResult.responseText.ifBlank { "Understood, sir." }
                }
                saveMessageAndHistory(text, responseMsg)
                scheduleBackgroundMemoryAndSummarization(text, responseMsg)
                emit(responseMsg)
                return@flow
            }
            is co.aura.agent.AgentPlanResult.RequiresAi -> {
                val userMsg = ChatMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    sessionId = "session_default",
                    sender = MessageSender.USER,
                    content = text,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.SENT
                )
                inMemoryHistory.add(userMsg)
                try {
                    conversationRepository.saveMessage(userMsg)
                } catch (e: Exception) {}

                contextBuilder.buildContext(planResult.agentContext)
            }
        }

        val chunker = SentenceChunker()
        val accumulatedFullResponse = StringBuilder()
        var isActionDecisionJson: Boolean? = null
        val initialBuffer = StringBuilder()

        try {
            aiProvider.streamResponse(contextPrompt).collect { token ->
                accumulatedFullResponse.append(token)

                if (isActionDecisionJson == null) {
                    initialBuffer.append(token)
                    val trimmedInit = initialBuffer.toString().trim()
                    if (trimmedInit.startsWith("{") || trimmedInit.startsWith("```")) {
                        if (trimmedInit.length > 30 || trimmedInit.contains("\n") || trimmedInit.contains("\"type\"") || trimmedInit.contains("\"actionType\"")) {
                            isActionDecisionJson = true
                        }
                    } else if (trimmedInit.isNotEmpty()) {
                        isActionDecisionJson = false
                        val chunks = chunker.append(initialBuffer.toString())
                        for (c in chunks) {
                            emit(c)
                        }
                        initialBuffer.clear()
                    }
                } else if (isActionDecisionJson == true) {
                    // Buffering action JSON in accumulatedFullResponse
                } else {
                    val chunks = chunker.append(token)
                    for (c in chunks) {
                        emit(c)
                    }
                }
            }

            if (isActionDecisionJson == true || (isActionDecisionJson == null && initialBuffer.toString().trim().startsWith("{"))) {
                val fullRaw = accumulatedFullResponse.toString().trim()
                val cleanedJson = fullRaw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val decision = if (cleanedJson.startsWith("{") && cleanedJson.endsWith("}")) {
                    parseAssistantDecision(cleanedJson)
                } else {
                    null
                } ?: AssistantDecision(
                    type = "NORMAL_CONVERSATION",
                    response = fullRaw
                )

                val actionResults = mutableListOf<Pair<Action, ActionResult>>()
                for (action in decision.actions) {
                    if (!actionValidator.validateAction(action)) {
                        actionResults.add(action to ActionResult.Failure("Sir, the requested action is invalid."))
                        break
                    }
                    val actionResult: ActionResult = when (action) {
                        is CallContactAction -> resolveAndConfirmContact(action.contactName, "CALL", null)
                        is SendSmsAction     -> resolveAndConfirmContact(action.contactName, "SMS", action.message)
                        is SendWhatsAppAction -> resolveAndConfirmContact(action.contactName, "WHATSAPP", action.message)
                        is PlayMediaAction   -> handlePlayMediaRequest(action)
                        else -> actionRouter.routeAction(action)
                    }
                    actionResults.add(action to actionResult)
                    lastActionType = action.actionType
                    lastActionStatus = if (actionResult is ActionResult.Success) "Success" else "Failure"
                    if (actionResult is ActionResult.Ambiguity) {
                        if (action is OpenAppAction) {
                            pendingResolutionCandidates = actionResult.candidates
                            pendingResolutionQuery = aliasResolver.getCanonicalKey(action.appName) ?: aliasResolver.normalize(action.appName)
                        }
                        break
                    }
                    if (actionResult is ActionResult.Failure) break
                }

                val finalResponseText = if (actionResults.isNotEmpty()) {
                    finalizeResponse(decision, actionResults)
                } else {
                    decision.response
                }

                // Pass through SentenceChunker so TTS queue receives sequential sentence chunks
                val sentenceChunks = chunker.append(finalResponseText) + chunker.flush()
                if (sentenceChunks.isEmpty() && finalResponseText.isNotBlank()) {
                    emit(finalResponseText)
                } else {
                    for (sentence in sentenceChunks) {
                        emit(sentence)
                    }
                }

                val assistantMsg = ChatMessage(
                    id = "msg_${System.currentTimeMillis() + 1}",
                    sessionId = "session_default",
                    sender = MessageSender.ASSISTANT,
                    content = finalResponseText,
                    timestamp = System.currentTimeMillis() + 1,
                    status = MessageStatus.SENT
                )
                inMemoryHistory.add(assistantMsg)
                try {
                    conversationRepository.saveMessage(assistantMsg)
                } catch (e: Exception) {}

                // CRITICAL: Schedule memory extraction and summarization AFTER response is handed to TTS
                scheduleBackgroundMemoryAndSummarization(text, finalResponseText)
            } else {
                val remaining = chunker.flush()
                for (c in remaining) {
                    emit(c)
                }

                val fullAssistantText = accumulatedFullResponse.toString().trim()
                if (fullAssistantText.isNotEmpty()) {
                    val assistantMsg = ChatMessage(
                        id = "msg_${System.currentTimeMillis() + 1}",
                        sessionId = "session_default",
                        sender = MessageSender.ASSISTANT,
                        content = fullAssistantText,
                        timestamp = System.currentTimeMillis() + 1,
                        status = MessageStatus.SENT
                    )
                    inMemoryHistory.add(assistantMsg)
                    try {
                        conversationRepository.saveMessage(assistantMsg)
                    } catch (e: Exception) {}

                    // CRITICAL: Schedule memory extraction and summarization AFTER response is handed to TTS
                    scheduleBackgroundMemoryAndSummarization(text, fullAssistantText)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
