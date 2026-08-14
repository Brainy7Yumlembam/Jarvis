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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

// ─── Test-local fakes (none of these duplicate Fakes.kt) ─────────────────────

/** Tracks which actions were executed and can fail on a specific actionType. */
private class RecordingActionExecutor : ActionExecutor {
    val executed = mutableListOf<Action>()
    var failOn: String? = null
    override suspend fun executeAction(action: Action): ActionResult {
        executed.add(action)
        return if (failOn != null && action.actionType == failOn)
            ActionResult.Failure("Simulated failure for ${action.actionType}")
        else
            ActionResult.Success("Done: ${action.actionType}")
    }
}

private class FakeContactResolver(
    private val contacts: Map<String, List<ContactInfo>>
) : ContactResolver {
    override suspend fun resolveContact(name: String): List<ContactInfo> =
        contacts.entries
            .filter { it.key.contains(name, ignoreCase = true) || name.contains(it.key, ignoreCase = true) }
            .flatMap { it.value }
}

/** Configurable AIProvider — response JSON can be set per-test. */
private class ConfigurableAIProvider(var responseJson: String) : co.aura.ai.AIProvider {
    override suspend fun generateResponse(prompt: String): String = responseJson
    override fun streamResponse(prompt: String): Flow<String> = flowOf(responseJson)
    override suspend fun summarize(text: String): String = "Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}

private object FakePersonalityEngine : PersonalityEngine {
    override fun setMode(mode: PersonalityMode) {}
    override fun getMode(): PersonalityMode = PersonalityMode.ASSISTANT
    override fun setPersonality(personality: AssistantPersonality) {}
    override fun getPersonality(): AssistantPersonality = AssistantPersonality()
    override fun getSystemInstructions(conversationMode: ConversationMode): String = "You are JARVIS."
}

private object FakeContextBuilder : ContextBuilder {
    override fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        recentMessages: List<ChatMessage>,
        relevantMemories: List<MemoryFragment>,
        currentRequest: String
    ): String = currentRequest

    override fun buildContext(
        systemInstructions: String,
        personalityPrompt: String,
        conversationMode: ConversationMode,
        relevantMemories: List<MemoryFragment>,
        recentMessages: List<ChatMessage>,
        recentActionContext: String?,
        currentRequest: String
    ): String = currentRequest
}

// ─── Builder helper ───────────────────────────────────────────────────────────

private fun buildManager(
    aiJson: String,
    contactResolver: ContactResolver = FakeContactResolver(emptyMap()),
    executor: ActionExecutor = RecordingActionExecutor()
): ConversationManagerImpl {
    val commandBus = CommandBusImpl()
    val actionRouter = ActionRouterImpl(commandBus, executor)
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    return ConversationManagerImpl(
        aiProvider = ConfigurableAIProvider(aiJson),
        memoryManager = FakeMemoryManager(),
        personalityEngine = FakePersonalityEngine,
        conversationRepository = FakeConversationRepository(),
        contextBuilder = FakeContextBuilder,
        actionParser = ActionParserImpl(json),
        actionValidator = ActionValidatorImpl(),
        actionRouter = actionRouter,
        speechNormalizer = SpeechCommandNormalizer(),
        securityManager = FakeSecurityManager(),
        aliasResolver = AppAliasResolver(),
        contactResolver = contactResolver
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ACTION PARSER TESTS
// ─────────────────────────────────────────────────────────────────────────────

class ActionParserTest {
    private val parser = ActionParserImpl(Json { ignoreUnknownKeys = true; isLenient = true })

    @Test fun `parseAction CALL_CONTACT returns CallContactAction`() {
        val action = parser.parseAction("""{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}""")
        assertIs<CallContactAction>(action)
        assertEquals("Alice", (action as CallContactAction).contactName)
    }

    @Test fun `parseAction SEND_SMS with contactName returns SendSmsAction`() {
        val action = parser.parseAction("""{"action":"SEND_SMS","parameters":{"contactName":"Bob","message":"Hello!"}}""")
        assertIs<SendSmsAction>(action)
        assertEquals("Bob", (action as SendSmsAction).contactName)
        assertEquals("Hello!", action.message)
    }

    @Test fun `parseAction SEND_WHATSAPP returns SendWhatsAppAction`() {
        val action = parser.parseAction("""{"action":"SEND_WHATSAPP","parameters":{"contactName":"Charlie","message":"Hey"}}""")
        assertIs<SendWhatsAppAction>(action)
        assertEquals("Charlie", (action as SendWhatsAppAction).contactName)
    }

    @Test fun `parseAction PLAY_MEDIA returns PlayMediaAction`() {
        val action = parser.parseAction("""{"action":"PLAY_MEDIA","parameters":{"query":"Numb Linkin Park"}}""")
        assertIs<PlayMediaAction>(action)
        assertEquals("Numb Linkin Park", (action as PlayMediaAction).query)
    }

    @Test fun `parseAction SET_ALARM with hour_minute returns SetAlarmAction`() {
        val action = parser.parseAction("""{"action":"SET_ALARM","parameters":{"hour":7,"minute":30,"label":"Wake up"}}""")
        assertIs<SetAlarmAction>(action)
        val alarm = action as SetAlarmAction
        assertEquals(7, alarm.hour)
        assertEquals(30, alarm.minute)
        assertEquals("Wake up", alarm.label)
    }

    @Test fun `parseAction SET_TIMER returns SetTimerAction`() {
        val action = parser.parseAction("""{"action":"SET_TIMER","parameters":{"durationSeconds":300,"label":"Pasta"}}""")
        assertIs<SetTimerAction>(action)
        assertEquals(300L, (action as SetTimerAction).durationSeconds)
    }

    @Test fun `parseAction VOLUME_UP returns VolumeUpAction`() {
        val action = parser.parseAction("""{"action":"VOLUME_UP","parameters":{}}""")
        assertIs<VolumeUpAction>(action)
    }

    @Test fun `parseAction SET_VOLUME returns SetVolumeAction with level`() {
        val action = parser.parseAction("""{"action":"SET_VOLUME","parameters":{"level":80}}""")
        assertIs<SetVolumeAction>(action)
        assertEquals(80, (action as SetVolumeAction).level)
    }

    @Test fun `parseAction TOGGLE_FLASHLIGHT true returns enabled`() {
        val action = parser.parseAction("""{"action":"TOGGLE_FLASHLIGHT","parameters":{"enabled":true}}""")
        assertIs<ToggleFlashlightAction>(action)
        assertTrue((action as ToggleFlashlightAction).enabled)
    }

    @Test fun `parseAction PAUSE_MEDIA returns PauseMediaAction`() {
        val action = parser.parseAction("""{"action":"PAUSE_MEDIA","parameters":{}}""")
        assertIs<PauseMediaAction>(action)
    }

    @Test fun `parseAction unknown type returns non-null result`() {
        val action = parser.parseAction("""{"action":"UNKNOWN_THING","parameters":{}}""")
        assertNotNull(action)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ACTION VALIDATOR TESTS
// ─────────────────────────────────────────────────────────────────────────────

class ActionValidatorTest {
    private val validator = ActionValidatorImpl()

    @Test fun `valid OpenAppAction passes`() { assertTrue(validator.validateAction(OpenAppAction("YouTube"))) }
    @Test fun `blank OpenAppAction fails`() { assertFalse(validator.validateAction(OpenAppAction(""))) }
    @Test fun `CallContactAction with name passes`() { assertTrue(validator.validateAction(CallContactAction("Alice"))) }
    @Test fun `CallContactAction blank fails`() { assertFalse(validator.validateAction(CallContactAction(""))) }
    @Test fun `SendSmsAction valid passes`() { assertTrue(validator.validateAction(SendSmsAction("Bob", "Hello"))) }
    @Test fun `SendSmsAction blank contact fails`() { assertFalse(validator.validateAction(SendSmsAction("", "Hi"))) }
    @Test fun `SendWhatsAppAction valid passes`() { assertTrue(validator.validateAction(SendWhatsAppAction("Charlie", "Hey"))) }
    @Test fun `PlayMediaAction with query passes`() { assertTrue(validator.validateAction(PlayMediaAction("Numb"))) }
    @Test fun `PlayMediaAction blank fails`() { assertFalse(validator.validateAction(PlayMediaAction(""))) }
    @Test fun `SetAlarmAction valid passes`() { assertTrue(validator.validateAction(SetAlarmAction(7, 30))) }
    @Test fun `SetAlarmAction invalid hour fails`() { assertFalse(validator.validateAction(SetAlarmAction(25, 0))) }
    @Test fun `SetAlarmAction invalid minute fails`() { assertFalse(validator.validateAction(SetAlarmAction(7, 61))) }
    @Test fun `SetTimerAction positive passes`() { assertTrue(validator.validateAction(SetTimerAction(60))) }
    @Test fun `SetTimerAction zero fails`() { assertFalse(validator.validateAction(SetTimerAction(0))) }
    @Test fun `SetVolumeAction 0 to 100 passes`() {
        assertTrue(validator.validateAction(SetVolumeAction(0)))
        assertTrue(validator.validateAction(SetVolumeAction(50)))
        assertTrue(validator.validateAction(SetVolumeAction(100)))
    }
    @Test fun `SetVolumeAction out of range fails`() {
        assertFalse(validator.validateAction(SetVolumeAction(101)))
        assertFalse(validator.validateAction(SetVolumeAction(-1)))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CONTACT RESOLVER TESTS
// ─────────────────────────────────────────────────────────────────────────────

class ContactResolverTest {
    private val resolver = FakeContactResolver(
        mapOf(
            "Alice" to listOf(ContactInfo("Alice", "5551001")),
            "Bob" to listOf(ContactInfo("Bob", "5552002")),
            "Charlie" to listOf(
                ContactInfo("Charlie Smith", "5553001"),
                ContactInfo("Charlie Brown", "5553002")
            )
        )
    )

    @Test fun `single exact match returns one contact`() { runBlocking {
        val result = resolver.resolveContact("Alice")
        assertEquals(1, result.size)
        assertEquals("Alice", result[0].name)
    }}

    @Test fun `no match returns empty list`() { runBlocking {
        assertTrue(resolver.resolveContact("Zara").isEmpty())
    }}

    @Test fun `multiple partial matches returns all candidates`() { runBlocking {
        assertEquals(2, resolver.resolveContact("Charlie").size)
    }}

    @Test fun `contact name does not contain a phone number`() { runBlocking {
        val result = resolver.resolveContact("Alice")
        assertFalse(
            result[0].name.matches(Regex(".*\\d{7,}.*")),
            "Contact name must not contain a phone number: ${result[0].name}"
        )
    }}
}

// ─────────────────────────────────────────────────────────────────────────────
// CONFIRMATION FLOW TESTS
// ─────────────────────────────────────────────────────────────────────────────

class ConfirmationFlowTest {

    @Test fun `single contact call requires yes confirmation and executes exactly once`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"I will call Alice.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Alice" to listOf(ContactInfo("Alice", "5551001")))),
            executor = executor
        )
        val confirmMsg = manager.processUserMessage("call Alice")
        assertTrue(confirmMsg.contains("Alice", ignoreCase = true), "Expected confirmation prompt, got: $confirmMsg")
        assertTrue(executor.executed.isEmpty(), "Call must NOT execute before confirmation")

        manager.processUserMessage("yes")
        assertEquals(1, executor.executed.size, "Call must execute exactly once")
        val action = executor.executed.first()
        assertIs<CallAction>(action)
    }}

    @Test fun `second yes after confirmation does NOT execute again`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Alice" to listOf(ContactInfo("Alice", "5551001")))),
            executor = executor
        )
        manager.processUserMessage("call Alice")
        manager.processUserMessage("yes") // executes once — pending cleared
        manager.processUserMessage("yes") // second yes goes to AI, not confirmation
        assertEquals(1, executor.executed.size, "Call must execute exactly once only")
    }}

    @Test fun `no cancels confirmation and no call is placed`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Alice" to listOf(ContactInfo("Alice", "5551001")))),
            executor = executor
        )
        manager.processUserMessage("call Alice")
        val cancelMsg = manager.processUserMessage("no")
        assertTrue(executor.executed.isEmpty(), "No call should be placed after no")
        assertTrue(
            cancelMsg.contains("cancel", ignoreCase = true) || cancelMsg.contains("cancelled", ignoreCase = true),
            "Expected cancellation message, got: $cancelMsg"
        )
    }}

    @Test fun `phone number never appears in confirmation prompt`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Alice" to listOf(ContactInfo("Alice", "9876543210")))),
            executor = executor
        )
        val confirmMsg = manager.processUserMessage("call Alice")
        assertFalse(confirmMsg.contains("9876543210"), "Phone number must NOT appear in confirmation: $confirmMsg")
    }}

    @Test fun `phone number never appears in success response`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Alice" to listOf(ContactInfo("Alice", "9876543210")))),
            executor = executor
        )
        manager.processUserMessage("call Alice")
        val successMsg = manager.processUserMessage("yes")
        assertFalse(successMsg.contains("9876543210"), "Phone number must NOT appear in success response: $successMsg")
    }}

    @Test fun `SMS confirmation includes message text and contact name`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"I'll send that.","actions":[{"action":"SEND_SMS","parameters":{"contactName":"Bob","message":"Hello there"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Bob" to listOf(ContactInfo("Bob", "5552002")))),
            executor = executor
        )
        val confirmMsg = manager.processUserMessage("send SMS to Bob saying Hello there")
        assertTrue(confirmMsg.contains("Bob", ignoreCase = true), "Confirmation must mention contact name: $confirmMsg")
        assertTrue(confirmMsg.contains("Hello there", ignoreCase = true), "Confirmation must include message: $confirmMsg")
        assertFalse(confirmMsg.contains("5552002"), "Phone number must not appear in confirmation: $confirmMsg")
    }}

    @Test fun `WhatsApp confirmation does not leak phone number`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Messaging Bob.","actions":[{"action":"SEND_WHATSAPP","parameters":{"contactName":"Bob","message":"Hey!"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Bob" to listOf(ContactInfo("Bob", "1112223333")))),
            executor = executor
        )
        val confirmMsg = manager.processUserMessage("WhatsApp Bob saying Hey!")
        assertFalse(confirmMsg.contains("1112223333"), "Phone number must NOT appear in WhatsApp confirmation: $confirmMsg")
    }}
}

// ─────────────────────────────────────────────────────────────────────────────
// CONTACT AMBIGUITY TESTS
// ─────────────────────────────────────────────────────────────────────────────

class ContactAmbiguityTest {

    @Test fun `multiple contacts produce ambiguity prompt and block execution`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling Charlie.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Charlie"}}]}""",
            contactResolver = FakeContactResolver(mapOf(
                "Charlie" to listOf(
                    ContactInfo("Charlie Smith", "5553001"),
                    ContactInfo("Charlie Brown", "5553002")
                )
            )),
            executor = executor
        )
        val response = manager.processUserMessage("call Charlie")
        assertTrue(executor.executed.isEmpty(), "Must not call with ambiguous contacts")
        assertTrue(
            response.contains("Charlie Smith", ignoreCase = true) || response.contains("Charlie Brown", ignoreCase = true),
            "Ambiguity message should list candidates: $response"
        )
    }}

    @Test fun `unknown contact returns failure without calling`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling Zara.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Zara"}}]}""",
            contactResolver = FakeContactResolver(emptyMap()),
            executor = executor
        )
        val response = manager.processUserMessage("call Zara")
        assertTrue(executor.executed.isEmpty(), "Must not call unknown contact")
        assertTrue(
            response.contains("couldn't find", ignoreCase = true) || response.contains("Zara", ignoreCase = true),
            "Should report contact not found: $response"
        )
    }}

    @Test fun `single contact resolves and prompts for confirmation without executing`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{"type":"ACTION","response":"Calling Alice.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}""",
            contactResolver = FakeContactResolver(mapOf("Alice" to listOf(ContactInfo("Alice", "5551001")))),
            executor = executor
        )
        val response = manager.processUserMessage("call Alice")
        assertTrue(executor.executed.isEmpty(), "Single contact must require confirmation before calling")
        assertTrue(response.contains("Alice", ignoreCase = true), "Should mention contact name: $response")
    }}
}

// ─────────────────────────────────────────────────────────────────────────────
// SEQUENTIAL ACTION TESTS
// ─────────────────────────────────────────────────────────────────────────────

class SequentialActionTest {

    @Test fun `multi-action chain stops when first action fails`() { runBlocking {
        val executor = RecordingActionExecutor().apply { failOn = "OPEN_APP" }
        val manager = buildManager(
            aiJson = """{
              "type":"ACTION","response":"Opening YouTube and playing Numb.",
              "actions":[
                {"action":"OPEN_APP","parameters":{"appName":"YouTube"}},
                {"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}
              ]
            }""",
            executor = executor
        )
        manager.processUserMessage("open YouTube and play Numb")
        // OPEN_APP fails; CommandBus retries it 3 times, then aborts chain before PLAY_MEDIA
        assertTrue(executor.executed.isNotEmpty(), "At least one action should be executed")
        assertTrue(executor.executed.all { it is OpenAppAction }, "Only OPEN_APP should be executed, no PLAY_MEDIA")
        assertFalse(executor.executed.any { it is PlayMediaAction }, "PLAY_MEDIA must not be executed after OPEN_APP failure")
    }}

    @Test fun `multi-action all succeed executes all in order`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            aiJson = """{
              "type":"ACTION","response":"Done.",
              "actions":[
                {"action":"VOLUME_UP","parameters":{}},
                {"action":"PLAY_MEDIA","parameters":{"query":"Numb"}}
              ]
            }""",
            executor = executor
        )
        manager.processUserMessage("volume up and play Numb")
        assertEquals(2, executor.executed.size, "Both actions should have executed")
        val first = executor.executed[0]
        val second = executor.executed[1]
        assertIs<VolumeUpAction>(first)
        assertIs<PlayMediaAction>(second)
    }}
}

// ─────────────────────────────────────────────────────────────────────────────
// ACTION FAILURE / VALIDATION TESTS
// ─────────────────────────────────────────────────────────────────────────────

class ActionFailureTest {

    @Test fun `validation failure means executor is never called`() { runBlocking {
        val executor = RecordingActionExecutor()
        val manager = buildManager(
            // hour 99 is invalid — ActionValidator must reject before executing
            aiJson = """{"type":"ACTION","response":"Setting alarm.","actions":[{"action":"SET_ALARM","parameters":{"hour":99,"minute":0}}]}""",
            executor = executor
        )
        manager.processUserMessage("set alarm at hour 99")
        assertTrue(executor.executed.isEmpty(), "Executor must not be called when validation fails")
    }}

    @Test fun `null contactResolver on contact action produces failure response`() { runBlocking {
        val executor = RecordingActionExecutor()
        val commandBus = CommandBusImpl()
        val actionRouter = ActionRouterImpl(commandBus, executor)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val manager = ConversationManagerImpl(
            aiProvider = ConfigurableAIProvider(
                """{"type":"ACTION","response":"Calling.","actions":[{"action":"CALL_CONTACT","parameters":{"contactName":"Alice"}}]}"""
            ),
            memoryManager = FakeMemoryManager(),
            personalityEngine = FakePersonalityEngine,
            conversationRepository = FakeConversationRepository(),
            contextBuilder = FakeContextBuilder,
            actionParser = ActionParserImpl(json),
            actionValidator = ActionValidatorImpl(),
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver(),
            contactResolver = null // no resolver — non-Android platform
        )
        val response = manager.processUserMessage("call Alice")
        assertTrue(executor.executed.isEmpty(), "No call should be placed without a resolver")
        assertTrue(
            response.contains("not available", ignoreCase = true) || response.contains("couldn't", ignoreCase = true),
            "Should report platform failure: $response"
        )
    }}
}
