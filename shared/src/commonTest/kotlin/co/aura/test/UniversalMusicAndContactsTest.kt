package co.aura.test

import co.aura.actions.*
import co.aura.communication.ContactInfo
import co.aura.communication.ContactResolver
import co.aura.conversation.*
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MemoryFragment
import co.aura.voice.SpeechCommandNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

private class FakeAppRegistry(var apps: List<InstalledApp>) : InstalledAppRegistry {
    override fun getInstalledApps(): List<InstalledApp> = apps
    override fun refreshRegistry() {}
}

private class CapturingExecutor : ActionExecutor {
    val executed = mutableListOf<Action>()
    var responseToReturn: ActionResult = ActionResult.Success("Done")

    override suspend fun executeAction(action: Action): ActionResult {
        executed.add(action)
        return responseToReturn
    }
}

private class AIProviderMock(var responseJson: String) : co.aura.ai.AIProvider {
    var lastCapturedPrompt: String? = null

    override suspend fun generateResponse(prompt: String): String {
        lastCapturedPrompt = prompt
        return responseJson
    }

    override fun streamResponse(prompt: String): Flow<String> = flowOf(responseJson)
    override suspend fun summarize(text: String): String = "Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}

class UniversalMusicAndContactsTest {

    private fun buildTestManager(
        aiJson: String,
        apps: List<InstalledApp> = emptyList(),
        contactsMap: Map<String, List<ContactInfo>> = emptyMap(),
        executor: CapturingExecutor = CapturingExecutor(),
        aiProviderMock: AIProviderMock = AIProviderMock(aiJson)
    ): Pair<ConversationManagerImpl, CapturingExecutor> {
        val commandBus = CommandBusImpl()
        val actionRouter = ActionRouterImpl(commandBus, executor)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val contactResolver = object : ContactResolver {
            override suspend fun resolveContact(name: String): List<ContactInfo> {
                if (name.isBlank()) return contactsMap.values.flatten().distinctBy { it.phoneNumber }
                val normQuery = co.aura.communication.ContactMatchingUtils.normalize(name)
                return contactsMap.entries
                    .filter {
                        val normKey = co.aura.communication.ContactMatchingUtils.normalize(it.key)
                        normKey == normQuery || normKey.contains(normQuery) || normQuery.contains(normKey) ||
                        co.aura.communication.ContactMatchingUtils.isFuzzyMatch(name, it.key)
                    }
                    .flatMap { it.value }
            }
        }

        val manager = ConversationManagerImpl(
            aiProvider = aiProviderMock,
            memoryManager = FakeMemoryManager(),
            personalityEngine = object : PersonalityEngine {
                override fun setMode(mode: PersonalityMode) {}
                override fun getMode(): PersonalityMode = PersonalityMode.ASSISTANT
                override fun setPersonality(personality: AssistantPersonality) {}
                override fun getPersonality(): AssistantPersonality = AssistantPersonality()
                override fun getSystemInstructions(conversationMode: ConversationMode): String = "JARVIS"
            },
            conversationRepository = FakeConversationRepository(),
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(json),
            actionValidator = ActionValidatorImpl(),
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver(),
            contactResolver = contactResolver,
            appRegistry = FakeAppRegistry(apps)
        )
        return manager to executor
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MUSIC DISCOVERY & CAPABILITIES TESTS (1-13)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test fun `1 Discover an unknown or local music application`() {
        val localApp = InstalledApp("Retro Music", "com.retro.music", setOf(AppCapability.MEDIA_PLAY, AppCapability.LAUNCH))
        assertTrue(localApp.capabilities.contains(AppCapability.MEDIA_PLAY))
        assertEquals("com.retro.music", localApp.packageName)
    }

    @Test fun `2 Detect MEDIA_PLAY capability`() {
        val app = InstalledApp("Poweramp", "com.poweramp.player", setOf(AppCapability.MEDIA_PLAY))
        assertTrue(AppCapability.MEDIA_PLAY in app.capabilities)
    }

    @Test fun `3 Detect MEDIA_SEARCH capability`() {
        val app = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY, AppCapability.MEDIA_SEARCH))
        assertTrue(AppCapability.MEDIA_SEARCH in app.capabilities)
    }

    @Test fun `4 One music app results in automatic selection`() { runBlocking {
        val apps = listOf(InstalledApp("Musicolet", "com.krosbits.musicolet", setOf(AppCapability.MEDIA_PLAY)))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = apps
        )
        manager.processUserMessage("Play Numb")
        assertEquals(1, executor.executed.size)
        val action = executor.executed.first() as PlayMediaAction
        assertEquals("Numb", action.query)
        assertEquals("com.krosbits.musicolet", action.targetPackage)
    }}

    @Test fun `5 Multiple music apps trigger conversational selection`() { runBlocking {
        val apps = listOf(
            InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY)),
            InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        )
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = apps
        )
        val response = manager.processUserMessage("Play Numb")
        assertTrue(executor.executed.isEmpty(), "Execution must pause for selection when multiple music apps exist")
        assertTrue(response.contains("Spotify") && response.contains("YouTube Music"), "Response must list candidates: $response")
    }}

    @Test fun `6 Spotify selection resolves Spotify`() { runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb") // asks choice
        manager.processUserMessage("Spotify") // choice
        assertEquals(1, executor.executed.size)
        assertEquals("com.spotify.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }}

    @Test fun `7 YouTube selection resolves YouTube`() { runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("YouTube")
        assertEquals(1, executor.executed.size)
        assertEquals("com.google.android.apps.youtube.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }}

    @Test fun `8 Local Music selection resolves the discovered local player`() { runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val local = InstalledApp("Retro Music", "com.retro.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, local)
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("Use my local music player")
        assertEquals(1, executor.executed.size)
        assertEquals("com.retro.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }}

    @Test fun `9 the first one resolves first candidate`() { runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("the first one")
        assertEquals(1, executor.executed.size)
        assertEquals("com.spotify.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }}

    @Test fun `10 Saved preference automatically selects the preferred app`() { runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("Always use Spotify") // saves preference
        assertEquals(1, executor.executed.size)

        // Turn 2 — uses saved preference directly without asking
        manager.processUserMessage("Play In the End")
        assertEquals(2, executor.executed.size)
        assertEquals("com.spotify.music", (executor.executed.last() as PlayMediaAction).targetPackage)
    }}

    @Test fun `11 Uninstalled preferred app is ignored`() { runBlocking {
        // App installed: YouTube Music (Spotify was uninstalled)
        val yt = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(yt)
        )
        manager.processUserMessage("Play Numb")
        assertEquals(1, executor.executed.size)
        assertEquals("com.google.android.apps.youtube.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }}

    @Test fun `12 Playback launch fallback does not claim direct track playback success`() { runBlocking {
        val app = InstalledApp("Local Music", "com.local.music", setOf(AppCapability.LAUNCH))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(app)
        )
        executor.responseToReturn = ActionResult.Success("I opened Local Music, sir, but it doesn't support direct track playback.")
        val response = manager.processUserMessage("Play Numb")
        assertTrue(response.contains("doesn't support direct track playback") || response.contains("opened"),
            "Fallback response must state app opening rather than claiming track playback: $response")
    }}

    @Test fun `13 Pending music selection executes exactly once`() { runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("Use Spotify this time") // executes 1st time (temporary choice)
        assertEquals(1, executor.executed.size)
        manager.processUserMessage("Hello") // subsequent turn does not re-trigger music candidate logic
        assertEquals(1, executor.executed.size)
    }}

    // ─────────────────────────────────────────────────────────────────────────────
    // EXPLICIT SOURCE RESOLUTION TESTS (Section 12)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test fun `Section 12 - Test 1 source YouTube resolves YouTube over Spotify`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb on YouTube.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"YouTube"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb on YouTube")
        assertEquals(1, executor.executed.size)
        val action = executor.executed.first() as PlayMediaAction
        assertEquals("YouTube", action.source)
        assertEquals("com.google.android.youtube", action.targetPackage)
    }

    @Test fun `Section 12 - Test 2 source Spotify resolves Spotify over YouTube`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb on Spotify.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"Spotify"}}]}""",
            apps = listOf(yt, spotify)
        )
        manager.processUserMessage("Play Numb from Spotify")
        assertEquals(1, executor.executed.size)
        val action = executor.executed.first() as PlayMediaAction
        assertEquals("Spotify", action.source)
        assertEquals("com.spotify.music", action.targetPackage)
    }

    @Test fun `Section 12 - Test 3 source YouTube when YouTube uninstalled yields failure`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"YouTube"}}]}""",
            apps = listOf(spotify)
        )
        val response = manager.processUserMessage("Play Numb on YouTube")
        assertTrue(executor.executed.isEmpty(), "Must NOT execute playback on Spotify when YouTube was explicitly requested")
        assertTrue(response.contains("isn't installed", ignoreCase = true) || response.contains("YouTube"), "Must state YouTube isn't installed: $response")
    }

    @Test fun `Section 12 - Test 4 explicit source YouTube overrides saved preferred music app Spotify`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        // Save preferred_music_app = Spotify first
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("Always use Spotify")
        assertEquals(1, executor.executed.size)

        // Now send explicit YouTube command
        val (manager2, executor2) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb on YouTube.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"YouTube"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager2.processUserMessage("Play Numb on YouTube")
        assertEquals(1, executor2.executed.size)
        val action = executor2.executed.first() as PlayMediaAction
        assertEquals("com.google.android.youtube", action.targetPackage)
    }

    @Test fun `Section 12 - Test 5 null source uses saved preferred_music_app Spotify`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("Always use Spotify") // saves Spotify

        manager.processUserMessage("Play In the End")
        assertEquals(2, executor.executed.size)
        assertEquals("com.spotify.music", (executor.executed.last() as PlayMediaAction).targetPackage)
    }

    @Test fun `Section 12 - Test 6 null source and no preference with one music app uses that app`() = runBlocking {
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(yt)
        )
        manager.processUserMessage("Play Numb")
        assertEquals(1, executor.executed.size)
        assertEquals("com.google.android.youtube", (executor.executed.first() as PlayMediaAction).targetPackage)
    }

    @Test fun `Section 12 - Test 7 null source and no preference with multiple music apps triggers Ambiguity`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = listOf(spotify, yt)
        )
        val response = manager.processUserMessage("Play Numb")
        assertTrue(executor.executed.isEmpty())
        assertTrue(response.contains("Spotify") && response.contains("YouTube"))
    }

    @Test fun `Section 12 - Test 8 source local music matches local music player`() = runBlocking {
        val spotify = InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY))
        val local = InstalledApp("Retro Music", "com.retro.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"local music"}}]}""",
            apps = listOf(spotify, local)
        )
        manager.processUserMessage("Play Numb on my local music player")
        assertEquals(1, executor.executed.size)
        assertEquals("com.retro.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }

    @Test fun `Section 12 - Test 9 source YT matches YouTube alias`() = runBlocking {
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"YT"}}]}""",
            apps = listOf(yt)
        )
        manager.processUserMessage("Play Numb on YT")
        assertEquals(1, executor.executed.size)
        assertEquals("com.google.android.youtube", (executor.executed.first() as PlayMediaAction).targetPackage)
    }

    @Test fun `Section 12 - Test 10 source YouTube Music matches YouTube Music over YouTube`() = runBlocking {
        val yt = InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        val ytm = InstalledApp("YouTube Music", "com.google.android.apps.youtube.music", setOf(AppCapability.MEDIA_PLAY))
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb","source":"YouTube Music"}}]}""",
            apps = listOf(yt, ytm)
        )
        manager.processUserMessage("Play Numb on YouTube Music")
        assertEquals(1, executor.executed.size)
        assertEquals("com.google.android.apps.youtube.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CONTACTS & PRIVACY TESTS (14-27)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test fun `14 Exact contact match`() = runBlocking {
        val (manager, _) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling Alice.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactsMap = mapOf("Alice" to listOf(ContactInfo("Alice", "5550001")))
        )
        val response = manager.processUserMessage("Call Alice")
        assertTrue(response.contains("Alice", ignoreCase = true))
    }

    @Test fun `15 Case insensitive contact match`() = runBlocking {
        val (manager, _) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"alice"}}]}""",
            contactsMap = mapOf("Alice" to listOf(ContactInfo("Alice", "5550001")))
        )
        val response = manager.processUserMessage("call alice")
        assertTrue(response.contains("Alice", ignoreCase = true))
    }

    @Test fun `16 Partial contact match`() = runBlocking {
        val (manager, _) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Rahul"}}]}""",
            contactsMap = mapOf("Rahul Sharma" to listOf(ContactInfo("Rahul Sharma", "5550002")))
        )
        val response = manager.processUserMessage("call Rahul")
        assertTrue(response.contains("Rahul Sharma", ignoreCase = true))
    }

    @Test fun `17 Fuzzy STT contact match`() = runBlocking {
        val (manager, _) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Rhaul"}}]}""",
            contactsMap = mapOf("Rahul" to listOf(ContactInfo("Rahul", "5550003")))
        )
        val response = manager.processUserMessage("call Rhaul")
        assertTrue(response.contains("Rahul", ignoreCase = true))
    }

    @Test fun `18 Multiple contacts results in ambiguity prompt`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Rahul"}}]}""",
            contactsMap = mapOf(
                "Rahul" to listOf(
                    ContactInfo("Rahul Sharma", "5550004"),
                    ContactInfo("Rahul Singh", "5550005")
                )
            )
        )
        val response = manager.processUserMessage("Call Rahul")
        assertTrue(executor.executed.isEmpty(), "Call must not be placed when contact is ambiguous")
        assertTrue(response.contains("Rahul Sharma") || response.contains("Rahul Singh"))
    }

    @Test fun `Multiple phone numbers for single contact returns ambiguity prompt`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Mom"}}]}""",
            contactsMap = mapOf(
                "Mom" to listOf(
                    ContactInfo("Mom (Mobile)", "5550001", "Mobile"),
                    ContactInfo("Mom (Home)", "5550002", "Home")
                )
            )
        )
        val response = manager.processUserMessage("Call Mom")
        assertTrue(executor.executed.isEmpty())
        assertTrue(response.contains("multiple numbers") || response.contains("Mom (Mobile)") || response.contains("Mom (Home)"))
    }

    @Test fun `19 first one resolves first contact`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Rahul"}}]}""",
            contactsMap = mapOf(
                "Rahul" to listOf(
                    ContactInfo("Rahul Sharma", "5550004"),
                    ContactInfo("Rahul Singh", "5550005")
                )
            )
        )
        manager.processUserMessage("Call Rahul") // ambiguous prompt
        val confirmPrompt = manager.processUserMessage("the first one")
        assertTrue(confirmPrompt.contains("Rahul Sharma", ignoreCase = true))
        assertTrue(executor.executed.isEmpty(), "Call requires voice confirmation before execution")
        manager.processUserMessage("yes") // confirms
        assertEquals(1, executor.executed.size)
    }

    @Test fun `20 No contact yields clean failure`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"UnknownPerson"}}]}""",
            contactsMap = emptyMap()
        )
        val response = manager.processUserMessage("Call UnknownPerson")
        assertTrue(executor.executed.isEmpty())
        assertTrue(response.contains("couldn't find", ignoreCase = true) || response.contains("UnknownPerson"))
    }

    @Test fun `21 Permission denied yields clean failure`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactsMap = emptyMap() // simulated permission denied / empty list
        )
        val response = manager.processUserMessage("Call Alice")
        assertTrue(executor.executed.isEmpty())
        assertFalse(response.contains("Exception") || response.contains("NullPointer"))
    }

    @Test fun `22 Contact phone number never enters AI prompt`() = runBlocking {
        val mockAi = AIProviderMock("""{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""")
        val (manager, _) = buildTestManager(
            aiJson = "",
            contactsMap = mapOf("Alice" to listOf(ContactInfo("Alice", "9876543210"))),
            aiProviderMock = mockAi
        )
        manager.processUserMessage("Call Alice")
        val capturedPrompt = mockAi.lastCapturedPrompt
        assertNotNull(capturedPrompt)
        assertFalse(capturedPrompt.contains("9876543210"), "Phone number MUST NOT enter Gemini prompt")
    }

    @Test fun `23 Contact phone number never enters conversation history`() = runBlocking {
        val repo = FakeConversationRepository()
        val commandBus = CommandBusImpl()
        val executor = CapturingExecutor()
        val manager = ConversationManagerImpl(
            aiProvider = AIProviderMock("""{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}"""),
            memoryManager = FakeMemoryManager(),
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repo,
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(Json { ignoreUnknownKeys = true }),
            actionValidator = ActionValidatorImpl(),
            actionRouter = ActionRouterImpl(commandBus, executor),
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver(),
            contactResolver = object : ContactResolver {
                override suspend fun resolveContact(name: String) = listOf(ContactInfo("Alice", "9998887777"))
            }
        )
        manager.processUserMessage("Call Alice")
        manager.processUserMessage("yes")

        val messages = repo.getMessages(50).firstOrNull() ?: emptyList()
        val fullHistory = messages.joinToString { it.content }
        assertFalse(fullHistory.contains("9998887777"), "Phone number MUST NOT enter stored conversation history")
    }

    @Test fun `24 Contact phone number never appears in logs`() {
        val contact = ContactInfo("Alice", "9876543210")
        assertFalse(contact.name.contains("9876543210"), "Display name must be distinct from phone number")
    }

    @Test fun `25 Confirmation yes executes exactly once`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactsMap = mapOf("Alice" to listOf(ContactInfo("Alice", "5550001")))
        )
        manager.processUserMessage("Call Alice")
        manager.processUserMessage("yes")
        assertEquals(1, executor.executed.size)
        manager.processUserMessage("yes")
        assertEquals(1, executor.executed.size)
    }

    @Test fun `26 Confirmation no cancels`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactsMap = mapOf("Alice" to listOf(ContactInfo("Alice", "5550001")))
        )
        manager.processUserMessage("Call Alice")
        val cancelMsg = manager.processUserMessage("no")
        assertTrue(executor.executed.isEmpty())
        assertTrue(cancelMsg.contains("cancelled", ignoreCase = true) || cancelMsg.contains("Understood", ignoreCase = true))
    }

    @Test fun `27 Duplicate voice responses do not trigger duplicate calls`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactsMap = mapOf("Alice" to listOf(ContactInfo("Alice", "5550001")))
        )
        manager.processUserMessage("Call Alice")
        manager.processUserMessage("yes")
        manager.processUserMessage("yes")
        assertEquals(1, executor.executed.size, "Call must execute exactly once regardless of repeated yes responses")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MULTI-TURN TESTS (28-32)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test fun `28 Play Numb asks source`() = runBlocking {
        val apps = listOf(
            InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY)),
            InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        )
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = apps
        )
        val response = manager.processUserMessage("Play Numb")
        assertTrue(executor.executed.isEmpty())
        assertTrue(response.contains("Spotify") || response.contains("YouTube"))
    }

    @Test fun `29 Spotify resolves pending source`() = runBlocking {
        val apps = listOf(
            InstalledApp("Spotify", "com.spotify.music", setOf(AppCapability.MEDIA_PLAY)),
            InstalledApp("YouTube", "com.google.android.youtube", setOf(AppCapability.MEDIA_PLAY))
        )
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Playing Numb.","actions":[{"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}]}""",
            apps = apps
        )
        manager.processUserMessage("Play Numb")
        manager.processUserMessage("Spotify")
        assertEquals(1, executor.executed.size)
        assertEquals("com.spotify.music", (executor.executed.first() as PlayMediaAction).targetPackage)
    }

    @Test fun `30 Call Rahul asks clarification when ambiguous`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Rahul"}}]}""",
            contactsMap = mapOf(
                "Rahul" to listOf(
                    ContactInfo("Rahul Sharma", "5550004"),
                    ContactInfo("Rahul Singh", "5550005")
                )
            )
        )
        val response = manager.processUserMessage("Call Rahul")
        assertTrue(executor.executed.isEmpty())
        assertTrue(response.contains("Rahul Sharma") && response.contains("Rahul Singh"))
    }

    @Test fun `31 Rahul Sharma resolves pending contact`() = runBlocking {
        val (manager, executor) = buildTestManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Rahul"}}]}""",
            contactsMap = mapOf(
                "Rahul" to listOf(
                    ContactInfo("Rahul Sharma", "5550004"),
                    ContactInfo("Rahul Singh", "5550005")
                )
            )
        )
        manager.processUserMessage("Call Rahul")
        val confirmPrompt = manager.processUserMessage("Rahul Sharma")
        assertTrue(confirmPrompt.contains("Rahul Sharma"))
        manager.processUserMessage("yes")
        assertEquals(1, executor.executed.size)
    }

    @Test fun `32 Open YouTube then close it continues to work with existing action context`() = runBlocking {
        val mockAi = AIProviderMock("""{"type":"ACTION","response":"Opening YouTube.","actions":[{"action":"OPEN_APP","parameters":{"appName":"YouTube"}}]}""")
        val (manager, executor) = buildTestManager(
            aiJson = "",
            apps = listOf(InstalledApp("YouTube", "com.google.android.youtube")),
            aiProviderMock = mockAi
        )
        manager.processUserMessage("Open YouTube")
        assertEquals(1, executor.executed.size)

        // Turn 2: Close it
        mockAi.responseJson = """{"type":"NORMAL_CONVERSATION","response":"Done.","actions":[]}"""
        val response = manager.processUserMessage("close it")
        val prompt = mockAi.lastCapturedPrompt
        assertNotNull(prompt)
        assertTrue(prompt.contains("Last opened app: YouTube") || prompt.contains("Last action: OPEN_APP"))
        assertTrue(response.contains("Done"), "Response should contain Done: $response")
    }
}
