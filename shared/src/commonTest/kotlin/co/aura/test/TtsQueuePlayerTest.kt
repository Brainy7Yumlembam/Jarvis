package co.aura.test

import co.aura.voice.DefaultTtsQueuePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TtsQueuePlayerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSequentialEnqueueAndPlayback() = runTest {
        val fakeTts = FakeTextToSpeechEngine()
        val spokenChunks = mutableListOf<String>()
        var playbackCompleted = false

        val queuePlayer = DefaultTtsQueuePlayer(
            ttsEngine = fakeTts,
            coroutineScope = this,
            onUtteranceStart = { spokenChunks.add(it) },
            onPlaybackComplete = { playbackCompleted = true }
        )

        queuePlayer.enqueue("Sentence one.")
        testScheduler.runCurrent()
        assertEquals(listOf("Sentence one."), spokenChunks)

        queuePlayer.enqueue("Sentence two.")
        testScheduler.runCurrent()
        assertEquals(listOf("Sentence one.", "Sentence two."), spokenChunks)

        queuePlayer.finishEnqueuing()
        testScheduler.runCurrent()
        assertTrue(playbackCompleted)
    }

    @Test
    fun testStopAndClearCancelsRemainingChunks() = runTest {
        val fakeTts = FakeTextToSpeechEngine()
        val spokenChunks = mutableListOf<String>()

        val queuePlayer = DefaultTtsQueuePlayer(
            ttsEngine = fakeTts,
            coroutineScope = this,
            onUtteranceStart = { spokenChunks.add(it) }
        )

        queuePlayer.enqueue("First sentence.")
        testScheduler.runCurrent()
        assertEquals(1, spokenChunks.size)

        // Stop immediately
        queuePlayer.stopAndClear()
        testScheduler.runCurrent()

        assertTrue(fakeTts.stopCalled)
        assertFalse(queuePlayer.isPlaying)
    }

    @Test
    fun testMultipleConsecutiveTurnsPlaybackWorksWithoutStopping() = runTest {
        val fakeTts = FakeTextToSpeechEngine()
        val spokenChunks = mutableListOf<String>()
        var completionCount = 0

        val queuePlayer = DefaultTtsQueuePlayer(
            ttsEngine = fakeTts,
            coroutineScope = this,
            onUtteranceStart = { spokenChunks.add(it) },
            onPlaybackComplete = { completionCount++ }
        )

        // Turn 1
        queuePlayer.enqueue("Turn 1 sentence.")
        testScheduler.runCurrent()
        queuePlayer.finishEnqueuing()
        testScheduler.runCurrent()

        assertEquals(listOf("Turn 1 sentence."), spokenChunks)
        assertEquals(1, completionCount)

        // Turn 2 — MUST play successfully and not be dropped!
        queuePlayer.enqueue("Turn 2 sentence.")
        testScheduler.runCurrent()
        queuePlayer.finishEnqueuing()
        testScheduler.runCurrent()

        assertEquals(listOf("Turn 1 sentence.", "Turn 2 sentence."), spokenChunks)
        assertEquals(2, completionCount)
    }
}
