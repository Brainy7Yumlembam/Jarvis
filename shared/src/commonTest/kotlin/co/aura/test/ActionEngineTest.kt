package co.aura.test

import co.aura.actions.*
import co.aura.ai.AIProvider
import co.aura.conversation.ContextBuilderImpl
import co.aura.conversation.ConversationManagerImpl
import co.aura.conversation.PersonalityEngineImpl
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ActionEngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val actionParser = ActionParserImpl(json)
    private val actionValidator = ActionValidatorImpl()

    @Test
    fun testParseOpenAppAction() {
        val rawJson = """
            {
              "type": "ACTION",
              "action": "OPEN_APP",
              "parameters": {
                "packageName": "com.whatsapp"
              }
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is OpenAppAction)
        assertEquals("com.whatsapp", action.packageName)
    }

    @Test
    fun testParseGetBatteryAction() {
        val rawJson = """
            {
              "type": "ACTION",
              "action": "GET_BATTERY"
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is GetBatteryAction)
        assertEquals("GET_BATTERY", action.actionType)
    }

    @Test
    fun testParseGetCurrentTimeAction() {
        val rawJson = """
            {
              "type": "ACTION",
              "action": "GET_CURRENT_TIME"
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is GetCurrentTimeAction)
        assertEquals("GET_CURRENT_TIME", action.actionType)
    }

    @Test
    fun testInvalidActionRejection() {
        val rawJson = """
            {
              "type": "ACTION",
              "action": "DO_SOMETHING_UNKNOWN"
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertFalse(actionValidator.validateAction(action))

        val invalidAction1 = OpenAppAction("invalid package name")
        assertTrue(actionValidator.validateAction(invalidAction1))

        val invalidAction2 = OpenAppAction("com.whatsapp space")
        assertTrue(actionValidator.validateAction(invalidAction2))

        val invalidAction3 = OpenAppAction("no_dots")
        assertTrue(actionValidator.validateAction(invalidAction3))

        val validAction1 = OpenAppAction("com.whatsapp")
        assertTrue(actionValidator.validateAction(validAction1))

        val validAction2 = OpenAppAction("co.aura.assistant_app")
        assertTrue(actionValidator.validateAction(validAction2))
    }

    @Test
    fun testMissingParameterRejection() {
        val action = OpenAppAction("")
        assertFalse(actionValidator.validateAction(action))
    }

    @Test
    fun testParseAndValidateAlarmAction() {
        val rawJson = """
            {
              "action": "SET_ALARM",
              "parameters": {
                "time": "7:00 AM"
              }
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is SetAlarmAction, "Expected SetAlarmAction, got: ${action?.javaClass?.simpleName}")
        val alarm = action as SetAlarmAction
        assertEquals(7, alarm.hour)
        assertEquals(0, alarm.minute)
        assertTrue(actionValidator.validateAction(alarm))
    }

    @Test
    fun testParseAndValidateCallAction() {
        val rawJson = """
            {
              "action": "CALL",
              "parameters": {
                "phoneNumber": "+1234567890"
              }
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is CallAction)
        assertEquals("+1234567890", action.phoneNumber)
        assertTrue(actionValidator.validateAction(action))
    }

    @Test
    fun testParseAndValidateSmsAction() {
        val rawJson = """
            {
              "action": "SEND_SMS",
              "parameters": {
                "phoneNumber": "555-0199",
                "message": "Hello Jarvis"
              }
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is SmsAction)
        assertEquals("555-0199", action.phoneNumber)
        assertEquals("Hello Jarvis", action.message)
        assertTrue(actionValidator.validateAction(action))
    }

    @Test
    fun testParseCallActionSynonyms() {
        val rawJson = """
            {
              "action": "CALL",
              "parameters": {
                "phone": "+9876543210"
              }
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is CallAction)
        assertEquals("+9876543210", action.phoneNumber)
    }

    @Test
    fun testParseSmsActionSynonyms() {
        val rawJson = """
            {
              "action": "SEND_SMS",
              "parameters": {
                "contact": "MW Vikash",
                "message": "Synonym test"
              }
            }
        """.trimIndent()
        val action = actionParser.parseAction(rawJson)
        assertNotNull(action)
        assertTrue(action is SendSmsAction, "Expected SendSmsAction, got: ${action?.javaClass?.simpleName}")
        val sms = action as SendSmsAction
        assertEquals("MW Vikash", sms.contactName)
        assertEquals("Synonym test", sms.message)
    }

    @Test
    fun testNormalConversationResponseDoesNotTriggerAction() {
        val rawText = "Certainly, sir. The sky is blue because of Rayleigh scattering."
        val action = actionParser.parseAction(rawText)
        assertNull(action)
    }

    @Test
    fun testActionExecutesExactlyOnce() = runTest {
        val spyExecutor = SpyActionExecutor()
        val commandBus = CommandBusImpl()
        val actionRouter = ActionRouterImpl(commandBus, spyExecutor)

        val action = GetBatteryAction()
        val result = actionRouter.routeAction(action)

        assertTrue(result is ActionResult.Success)
        assertEquals(1, spyExecutor.executionCount)
    }

    @Test
    fun testFailoverDoesNotDuplicateExecution() = runTest {
        var callCount = 0
        val failingAiProvider = object : FakeAIProvider() {
            override suspend fun generateResponse(prompt: String): String {
                callCount++
                if (callCount < 2) {
                    throw Exception("Temporary LLM error (simulating credential failover)")
                }
                return """{"type": "ACTION", "action": "GET_BATTERY"}"""
            }
        }

        val spyExecutor = SpyActionExecutor()
        val commandBus = CommandBusImpl()
        val actionRouter = ActionRouterImpl(commandBus, spyExecutor)
        val memoryManager = FakeMemoryManager()
        val repository = FakeConversationRepository()

        val retryingAiProvider = object : FakeAIProvider() {
            override suspend fun generateResponse(prompt: String): String {
                var lastErr: Exception? = null
                for (i in 1..3) {
                    try {
                        return failingAiProvider.generateResponse(prompt)
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                throw lastErr ?: Exception("Exhausted")
            }
        }

        val managerWithRetry = ConversationManagerImpl(
            aiProvider = retryingAiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repository,
            contextBuilder = ContextBuilderImpl(),
            actionParser = actionParser,
            actionValidator = actionValidator,
            actionRouter = actionRouter
        )

        val response = managerWithRetry.processUserMessage("Check battery status")
        assertEquals("Battery is 85%", response)
        assertEquals(1, spyExecutor.executionCount)
    }

    @Test
    fun testUnknownApplicationProducesSafeFailure() = runTest {
        val mockExecutor = object : ActionExecutor {
            override suspend fun executeAction(action: Action): ActionResult {
                return if (action is OpenAppAction && action.packageName == "com.unknown.app") {
                    ActionResult.Failure(
                        message = "Sir, com.unknown.app doesn't appear to be installed on this device."
                    )
                } else {
                    ActionResult.Success("Success")
                }
            }
        }

        val result = mockExecutor.executeAction(OpenAppAction("com.unknown.app"))
        assertTrue(result is ActionResult.Failure)
        assertEquals("Sir, com.unknown.app doesn't appear to be installed on this device.", result.message)
    }

    @Test
    fun testActionResultMapping() {
        val resultSuccess: ActionResult = ActionResult.Success("Your battery is currently at 85 percent, sir.")
        val resultFailure: ActionResult = ActionResult.Failure("Something went wrong, sir.")
        assertTrue(resultSuccess is ActionResult.Success)
        assertEquals("Your battery is currently at 85 percent, sir.", resultSuccess.message)
        assertTrue(resultFailure is ActionResult.Failure)
        assertEquals("Something went wrong, sir.", resultFailure.message)
    }

    private class SpyActionExecutor : ActionExecutor {
        var executionCount = 0
        override suspend fun executeAction(action: Action): ActionResult {
            executionCount++
            return when (action) {
                is GetBatteryAction -> ActionResult.Success("Battery is 85%")
                else -> ActionResult.Success("Success")
            }
        }
    }
}
