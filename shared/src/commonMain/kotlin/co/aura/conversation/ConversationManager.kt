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
import kotlinx.coroutines.flow.Flow
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
import co.aura.domain.model.AuraError
import co.aura.voice.SpeechCommandNormalizer
import co.aura.security.SecurityManager
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

private object NoOpSecurityManager : SecurityManager {
    override suspend fun authorizeAction(action: Action): Boolean = true
    override suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean = true
    override suspend fun saveSecureToken(key: String, token: String) {}
    override suspend fun getSecureToken(key: String): String? = null
}

interface ConversationManager {
    suspend fun processUserMessage(text: String): String
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
    private val appRegistry: InstalledAppRegistry? = null
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
        null
    )

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
        if (actionResults.isEmpty()) return decision.response
        // Collect all messages; failures/ambiguities interrupt the chain
        val parts = mutableListOf<String>()
        for ((action, result) in actionResults) {
            when (result) {
                is ActionResult.Success -> parts.add(result.message)
                is ActionResult.Ambiguity -> {
                    parts.add(result.message)
                    break
                }
                is ActionResult.Failure -> {
                    parts.add(result.message)
                    break
                }
            }
        }
        return if (parts.isNotEmpty()) parts.joinToString(" ") else decision.response
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
                    }
                    lastActionStatus = if (result is ActionResult.Success) "Success" else "Failure"
                    result.message
                } else {
                    lastActionStatus = "Cancelled"
                    val name = when (confirmation) {
                        is PendingConfirmation.Call -> confirmation.contactName
                        is PendingConfirmation.Sms -> confirmation.contactName
                        is PendingConfirmation.WhatsApp -> confirmation.contactName
                    }
                    "Understood, sir. The action for $name has been cancelled."
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

        val cleaned = text.trim().removeSuffix(".").removeSuffix("?").removeSuffix("!")
        val lower = cleaned.lowercase()

        // 1. Remember Command
        val rememberPrefixes = listOf("remember that ", "remember ")
        var isRemember = false
        var rememberContent = ""
        for (prefix in rememberPrefixes) {
            if (lower.startsWith(prefix)) {
                isRemember = true
                rememberContent = cleaned.substring(prefix.length).trim()
                break
            }
        }

        if (isRemember && rememberContent.isNotEmpty()) {
            val score = memoryManager.scoreMemoryImportance(rememberContent)
            val success = memoryManager.storeMemory(rememberContent, "PREFERENCE", score)
            val responseText = if (success) {
                "I will remember: $rememberContent"
            } else {
                "Sorry, I couldn't save that memory. (It may contain sensitive credentials)."
            }
            saveMessageAndHistory(text, responseText)
            return responseText
        }

        // 2. Forget Command
        val forgetPrefixes = listOf("forget that ", "forget ")
        var isForget = false
        var forgetTarget = ""
        for (prefix in forgetPrefixes) {
            if (lower.startsWith(prefix)) {
                isForget = true
                forgetTarget = cleaned.substring(prefix.length).trim()
                break
            }
        }

        if (isForget && forgetTarget.isNotEmpty()) {
            val allMemories = memoryManager.searchMemories("")
            val matches = allMemories.filter { it.content.lowercase().contains(forgetTarget.lowercase()) }

            val responseText = when {
                matches.isEmpty() -> {
                    "I couldn't find any memory matching: \"$forgetTarget\"."
                }
                matches.size == 1 -> {
                    val targetId = matches.first().id
                    memoryManager.forgetMemory(targetId)
                    "I have forgotten: \"${matches.first().content}\"."
                }
                else -> {
                    "I found multiple memories related to \"$forgetTarget\". Which one would you like me to forget?\n" +
                            matches.map { "- ${it.content}" }.joinToString("\n")
                }
            }
            saveMessageAndHistory(text, responseText)
            return responseText
        }

        // 3. Query Command
        val queryPhrases = listOf("what do you remember about me", "show me what you remember about me")
        if (queryPhrases.any { lower.contains(it) }) {
            val allMemories = memoryManager.searchMemories("")
            val responseText = if (allMemories.isEmpty()) {
                "I don't have any saved memories about you yet."
            } else {
                "Here is what I remember about you:\n" +
                        allMemories.map { "- ${it.content}" }.joinToString("\n")
            }
            saveMessageAndHistory(text, responseText)
            return responseText
        }

        // 4. Normal Conversation Message
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
        } catch (e: Exception) {
            // Ignore DB cache errors
        }

        // Extract Dynamic ConversationMode from settings
        val modeStr = securityManager.getSecureToken("conversation_mode") ?: "HYBRID"
        val conversationMode = try {
            ConversationMode.valueOf(modeStr)
        } catch (e: Exception) {
            ConversationMode.HYBRID
        }

        val systemInstruction = "You are JARVIS, a personal assistant built, created, and developed by $JARVIS_CREATOR_NAME."
        val personalityPrompt = personalityEngine.getSystemInstructions(conversationMode)
        
        // Retrieve relevant memories for the conversation
        val relevantMemories = memoryManager.retrieveRelevantMemories(text, limit = 5)

        val contextPrompt = contextBuilder.buildContext(
            systemInstructions = systemInstruction,
            personalityPrompt = personalityPrompt,
            conversationMode = conversationMode,
            relevantMemories = relevantMemories,
            recentMessages = inMemoryHistory.takeLast(10),
            recentActionContext = getRecentActionContextString(),
            currentRequest = normalizedActionText
        )

        val responseText = try {
            aiProvider.generateResponse(contextPrompt)
        } catch (e: AuraError) {
            throw e
        } catch (e: Exception) {
            co.aura.core.logging.AuraLogger.e(co.aura.core.logging.LogCategory.AI, "AI request failed", e)
            throw AuraError.AIRequestError("Sorry, sir. I'm having trouble reaching my AI service at the moment.")
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
    }
}
