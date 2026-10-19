package co.aura.test

import co.aura.actions.DeviceInfoType
import co.aura.actions.NoteOperationType
import co.aura.actions.ReminderOperationType
import co.aura.actions.SettingsPanel
import co.aura.router.CommandRoutingResult
import co.aura.router.LocalCalculator
import co.aura.router.LocalCommand
import co.aura.router.LocalCommandRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalCommandRouterTest {

    // ── 1. Local Calculator Tests ─────────────────────────────────────────────

    @Test
    fun testBasicArithmeticCalculation() {
        val multResult = LocalCalculator.evaluate("What is 245 multiplied by 18")
        assertNotNull(multResult)
        assertTrue(multResult.contains("4410") || multResult.contains("4,410"), "Expected 4410 but was $multResult")

        val addResult = LocalCalculator.evaluate("calculate 150 + 75")
        assertNotNull(addResult)
        assertTrue(addResult.contains("225"), "Expected 225 but was $addResult")

        val divResult = LocalCalculator.evaluate("what is 100 divided by 4")
        assertNotNull(divResult)
        assertTrue(divResult.contains("25"), "Expected 25 but was $divResult")
    }

    @Test
    fun testPercentageCalculation() {
        val pctResult = LocalCalculator.evaluate("What is 18 percent of 2500")
        assertNotNull(pctResult)
        assertTrue(pctResult.contains("450"), "Expected 450 but was $pctResult")

        val discountResult = LocalCalculator.evaluate("what is 15% off 200")
        assertNotNull(discountResult)
        assertTrue(discountResult.contains("170"), "Expected 170 but was $discountResult")
    }

    @Test
    fun testUnitConversions() {
        val kmToM = LocalCalculator.evaluate("convert 5 kilometres to metres")
        assertNotNull(kmToM)
        assertTrue(kmToM.contains("5000"), "Expected 5000 but was $kmToM")

        val milesToKm = LocalCalculator.evaluate("convert 10 miles to km")
        assertNotNull(milesToKm)
        assertTrue(milesToKm.contains("16.09") || milesToKm.contains("16.1"), "Expected ~16.09 km but was $milesToKm")

        val fToC = LocalCalculator.evaluate("convert 212 fahrenheit to celsius")
        assertNotNull(fToC)
        assertTrue(fToC.contains("100"), "Expected 100 C but was $fToC")
    }

    @Test
    fun testDateDifferences() {
        val christmasDiff = LocalCalculator.evaluate("how many days until Christmas")
        assertNotNull(christmasDiff)
        assertTrue(christmasDiff.contains("days until Christmas"), "Expected Christmas countdown but was $christmasDiff")
    }

    // ── 2. Wake Word and Voice Interruption Tests ─────────────────────────────

    @Test
    fun testWakeWordAcknowledgement() {
        val result = LocalCommandRouter.route("wake up jarvis")
        assertTrue(result is CommandRoutingResult.LocalDirectResponse)
        assertTrue(result.responseText.contains("At your service") || result.responseText.contains("JARVIS online"))

        val result2 = LocalCommandRouter.route("Hey Jarvis")
        assertTrue(result2 is CommandRoutingResult.LocalDirectResponse)
    }

    @Test
    fun testVoiceStopAndCancellation() {
        val stopResult = LocalCommandRouter.route("stop")
        assertTrue(stopResult is CommandRoutingResult.LocalDirectResponse)
        assertTrue(stopResult.responseText.contains("Cancelled") || stopResult.responseText.contains("Stopped"))

        val cancelResult = LocalCommandRouter.route("never mind")
        assertTrue(cancelResult is CommandRoutingResult.LocalDirectResponse)
    }

    @Test
    fun testTokenUsageQuery() {
        val tokenResult = LocalCommandRouter.route("how many tokens have i used")
        assertTrue(tokenResult is CommandRoutingResult.LocalDirectResponse)
        assertTrue(tokenResult.responseText.contains("JARVIS TOKEN CONSUMPTION REPORT"))
    }

    // ── 3. Device Info Tests ──────────────────────────────────────────────────

    @Test
    fun testCurrentTimeAndDate() {
        val timeResult = LocalCommandRouter.route("what time is it")
        assertTrue(timeResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.TIME, (timeResult.command as LocalCommand.DeviceInfo).type)

        val dateResult = LocalCommandRouter.route("what is today's date")
        assertTrue(dateResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.DATE, (dateResult.command as LocalCommand.DeviceInfo).type)
    }

    @Test
    fun testBatteryAndSystemStatus() {
        val batteryResult = LocalCommandRouter.route("what is my battery level")
        assertTrue(batteryResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.BATTERY, (batteryResult.command as LocalCommand.DeviceInfo).type)

        val chargingResult = LocalCommandRouter.route("is the phone charging")
        assertTrue(chargingResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.CHARGING, (chargingResult.command as LocalCommand.DeviceInfo).type)

        val storageResult = LocalCommandRouter.route("how much storage is left")
        assertTrue(storageResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.STORAGE, (storageResult.command as LocalCommand.DeviceInfo).type)

        val versionResult = LocalCommandRouter.route("what is my android version")
        assertTrue(versionResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.ANDROID_VERSION, (versionResult.command as LocalCommand.DeviceInfo).type)

        val netResult = LocalCommandRouter.route("am i connected to the internet")
        assertTrue(netResult is CommandRoutingResult.LocalExecution)
        assertEquals(DeviceInfoType.NETWORK_CONNECTIVITY, (netResult.command as LocalCommand.DeviceInfo).type)
    }

    // ── 4. Settings Subpanels and Restrictions ─────────────────────────────────

    @Test
    fun testSettingsPanels() {
        val wifiResult = LocalCommandRouter.route("open wifi settings")
        assertTrue(wifiResult is CommandRoutingResult.LocalExecution)
        assertEquals(SettingsPanel.WIFI, (wifiResult.command as LocalCommand.OpenSettings).panel)

        val btResult = LocalCommandRouter.route("open bluetooth settings")
        assertTrue(btResult is CommandRoutingResult.LocalExecution)
        assertEquals(SettingsPanel.BLUETOOTH, (btResult.command as LocalCommand.OpenSettings).panel)

        val soundResult = LocalCommandRouter.route("open sound settings")
        assertTrue(soundResult is CommandRoutingResult.LocalExecution)
        assertEquals(SettingsPanel.SOUND, (soundResult.command as LocalCommand.OpenSettings).panel)
    }

    @Test
    fun testModernAndroidRestrictionsHandledGracefully() {
        val wifiToggleResult = LocalCommandRouter.route("turn off wifi")
        assertTrue(wifiToggleResult is CommandRoutingResult.UnsupportedOperation)
        assertTrue(wifiToggleResult.explanation.contains("Android restrictions prevent"))
        assertNotNull(wifiToggleResult.fallbackCommand)
        assertTrue(wifiToggleResult.fallbackCommand is LocalCommand.OpenSettings)
        assertEquals(SettingsPanel.WIFI, (wifiToggleResult.fallbackCommand as LocalCommand.OpenSettings).panel)

        val mobileDataResult = LocalCommandRouter.route("turn off mobile data")
        assertTrue(mobileDataResult is CommandRoutingResult.UnsupportedOperation)
        assertTrue(mobileDataResult.explanation.contains("Android restricts direct mobile data toggling"))
    }

    // ── 5. Hardware Controls (Torch, Volume) ───────────────────────────────────

    @Test
    fun testFlashlightControls() {
        val torchOn = LocalCommandRouter.route("turn on the flashlight")
        assertTrue(torchOn is CommandRoutingResult.LocalExecution)
        assertEquals(true, (torchOn.command as LocalCommand.TorchControl).enabled)

        val torchOff = LocalCommandRouter.route("turn off torch")
        assertTrue(torchOff is CommandRoutingResult.LocalExecution)
        assertEquals(false, (torchOff.command as LocalCommand.TorchControl).enabled)
    }

    @Test
    fun testVolumeControls() {
        val volUp = LocalCommandRouter.route("volume up")
        assertTrue(volUp is CommandRoutingResult.LocalExecution)
        assertEquals(true, (volUp.command as LocalCommand.VolumeControl).isIncrease)

        val volMute = LocalCommandRouter.route("mute volume")
        assertTrue(volMute is CommandRoutingResult.LocalExecution)
        assertEquals(true, (volMute.command as LocalCommand.VolumeControl).isMute)

        val volPanel = LocalCommandRouter.route("show volume slider")
        assertTrue(volPanel is CommandRoutingResult.LocalExecution)
        assertEquals(true, (volPanel.command as LocalCommand.VolumeControl).showPanel)
    }

    // ── 6. App Launching & Web Navigation ─────────────────────────────────────

    @Test
    fun testStandardAppLaunching() {
        val cameraResult = LocalCommandRouter.route("open camera")
        assertTrue(cameraResult is CommandRoutingResult.LocalExecution)
        val cmd = cameraResult.command as LocalCommand.LaunchApp
        assertTrue(cmd.appTarget.contains("camera", ignoreCase = true) || cmd.appLabel == "Camera")

        val ytResult = LocalCommandRouter.route("launch youtube")
        assertTrue(ytResult is CommandRoutingResult.LocalExecution)
        assertEquals("com.google.android.youtube", (ytResult.command as LocalCommand.LaunchApp).appTarget)

        val waResult = LocalCommandRouter.route("open whatsapp")
        assertTrue(waResult is CommandRoutingResult.LocalExecution)
        assertEquals("com.whatsapp", (waResult.command as LocalCommand.LaunchApp).appTarget)
    }

    @Test
    fun testCameraCaptureRouting() {
        val photoResult = LocalCommandRouter.route("take a photo")
        assertTrue(photoResult is CommandRoutingResult.LocalExecution)
        assertTrue(photoResult.command is LocalCommand.CameraCapture)

        val captureResult = LocalCommandRouter.route("capture image")
        assertTrue(captureResult is CommandRoutingResult.LocalExecution)
        assertTrue(captureResult.command is LocalCommand.CameraCapture)

        val snapResult = LocalCommandRouter.route("snap a picture")
        assertTrue(snapResult is CommandRoutingResult.LocalExecution)
        assertTrue(snapResult.command is LocalCommand.CameraCapture)
    }

    @Test
    fun testUrlAndSearchNavigation() {
        val urlResult = LocalCommandRouter.route("open https://github.com")
        assertTrue(urlResult is CommandRoutingResult.LocalExecution)
        assertEquals("https://github.com", (urlResult.command as LocalCommand.OpenUrl).url)

        val searchResult = LocalCommandRouter.route("search google for kotlin multiplatform")
        assertTrue(searchResult is CommandRoutingResult.LocalExecution)
        assertEquals("kotlin multiplatform", (searchResult.command as LocalCommand.WebSearch).query)
    }

    // ── 7. Calls, SMS, WhatsApp, Email with Confirmation ──────────────────────

    @Test
    fun testDirectCallRequiresConfirmation() {
        val callResult = LocalCommandRouter.route("call 9876543210")
        assertTrue(callResult is CommandRoutingResult.LocalExecution)
        assertTrue(callResult.requiresConfirmation)
        assertTrue(callResult.sensitiveOperation)
        val cmd = callResult.command as LocalCommand.PhoneCall
        assertEquals("9876543210", cmd.number)
    }

    @Test
    fun testContactCallRouting() {
        val callContactResult = LocalCommandRouter.route("call Tony Stark")
        assertTrue(callContactResult is CommandRoutingResult.LocalExecution)
        assertTrue(callContactResult.requiresConfirmation)
        val cmd = callContactResult.command as LocalCommand.PhoneCall
        assertEquals("Tony Stark", cmd.contactName)
    }

    @Test
    fun testSmsMessageRouting() {
        val smsResult = LocalCommandRouter.route("text Pepper Potts saying I am on my way")
        assertTrue(smsResult is CommandRoutingResult.LocalExecution)
        assertTrue(smsResult.requiresConfirmation)
        val cmd = smsResult.command as LocalCommand.SmsMessage
        assertEquals("Pepper Potts", cmd.contactName)
        assertEquals("I am on my way", cmd.message)
    }

    @Test
    fun testWhatsAppMessageRouting() {
        val waResult = LocalCommandRouter.route("whatsapp Peter Parker that meet me at the lab")
        assertTrue(waResult is CommandRoutingResult.LocalExecution)
        assertTrue(waResult.requiresConfirmation)
        val cmd = waResult.command as LocalCommand.WhatsAppMessage
        assertEquals("Peter Parker", cmd.contactName)
        assertEquals("meet me at the lab", cmd.message)
    }

    @Test
    fun testSendEmailRouting() {
        val emailResult = LocalCommandRouter.route("send email to nick@shield.gov subject Avenger Initiative body Assemble immediately")
        assertTrue(emailResult is CommandRoutingResult.LocalExecution)
        assertTrue(emailResult.requiresConfirmation)
        val cmd = emailResult.command as LocalCommand.SendEmail
        assertEquals("nick@shield.gov", cmd.recipient)
        assertEquals("Avenger Initiative", cmd.subject)
        assertEquals("Assemble immediately", cmd.body)
    }

    // ── 8. Local Notes and Reminders ──────────────────────────────────────────

    @Test
    fun testNoteOperations() {
        val createNoteResult = LocalCommandRouter.route("take a note buy groceries and milk")
        assertTrue(createNoteResult is CommandRoutingResult.LocalExecution)
        val cmd = createNoteResult.command as LocalCommand.NoteManage
        assertEquals(NoteOperationType.CREATE, cmd.operation)
        assertEquals("buy groceries and milk", cmd.content)

        val readNotesResult = LocalCommandRouter.route("read my notes")
        assertTrue(readNotesResult is CommandRoutingResult.LocalExecution)
        assertEquals(NoteOperationType.READ_ALL, (readNotesResult.command as LocalCommand.NoteManage).operation)
    }

    @Test
    fun testReminderOperations() {
        val reminderResult = LocalCommandRouter.route("remind me to take my medicine")
        assertTrue(reminderResult is CommandRoutingResult.LocalExecution)
        val cmd = reminderResult.command as LocalCommand.ReminderManage
        assertEquals(ReminderOperationType.CREATE, cmd.operation)
        assertEquals("take my medicine", cmd.title)

        val listRemindersResult = LocalCommandRouter.route("show my reminders")
        assertTrue(listRemindersResult is CommandRoutingResult.LocalExecution)
        assertEquals(ReminderOperationType.LIST, (listRemindersResult.command as LocalCommand.ReminderManage).operation)
    }

    // ── 9. Ambiguity Detection ────────────────────────────────────────────────

    @Test
    fun testAmbiguityDetection() {
        val openAmbiguous = LocalCommandRouter.route("open")
        assertTrue(openAmbiguous is CommandRoutingResult.ClarificationNeeded)
        assertEquals("app_name", openAmbiguous.expectedEntity)

        val callAmbiguous = LocalCommandRouter.route("call")
        assertTrue(callAmbiguous is CommandRoutingResult.ClarificationNeeded)
        assertEquals("contact_or_number", callAmbiguous.expectedEntity)

        val messageAmbiguous = LocalCommandRouter.route("message")
        assertTrue(messageAmbiguous is CommandRoutingResult.ClarificationNeeded)
        assertEquals("message_details", messageAmbiguous.expectedEntity)
    }

    // ── 10. Hybrid Commands & AI Fallback ─────────────────────────────────────

    @Test
    fun testHybridCommandDetection() {
        val hybridResult = LocalCommandRouter.route("open youtube and explain quantum entanglement")
        assertTrue(hybridResult is CommandRoutingResult.RequiresAi)
        assertNotNull(hybridResult.preExecutionCommand)
        assertTrue(hybridResult.preExecutionCommand is LocalCommand.LaunchApp)
        assertEquals("com.google.android.youtube", (hybridResult.preExecutionCommand as LocalCommand.LaunchApp).appTarget)
        assertEquals("explain quantum entanglement", hybridResult.promptForGemini)
    }

    @Test
    fun testPureGeneralKnowledgeRoutesToAi() {
        val aiResult = LocalCommandRouter.route("who was the first Roman emperor")
        assertTrue(aiResult is CommandRoutingResult.RequiresAi)
        assertEquals(null, aiResult.preExecutionCommand)
        assertEquals("who was the first Roman emperor", aiResult.promptForGemini)

        val aiResult2 = LocalCommandRouter.route("write a python script to parse csv files")
        assertTrue(aiResult2 is CommandRoutingResult.RequiresAi)
        assertEquals(null, aiResult2.preExecutionCommand)
    }
}
