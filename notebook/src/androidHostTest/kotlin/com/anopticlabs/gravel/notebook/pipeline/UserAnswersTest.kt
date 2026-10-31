package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.alarms.FakeNotifier
import com.anopticlabs.gravel.notebook.alarms.FakeScheduler
import com.anopticlabs.gravel.notebook.alarms.FakeSettings
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.capture.AwaitingUser
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.awaitUser
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.routers.Grant
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RouterRef
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.Shares
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

class UserAnswersTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val crypto = FakeCryptographer(softwareCrypto())
    private val queue = QueueStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val routing = RoutingConfigStore(root, crypto)
    private val now = 1_790_089_200_000L
    private val hook = RouterConfig.Network("hook", "Hook", "https://example.test/notes")

    private val backend = object : CaptureBackend {
        override suspend fun checkReady() = Unit
        override suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture = error("unused")
        override suspend fun resume(resume: CaptureResume, onStage: (CaptureStage) -> Unit): BuiltCapture = error("unused")
    }
    private val publisher = CapturePublisher(
        notes, reminders, VoiceCompletionStore(root, crypto),
        ReminderAlarms(reminders, ReminderEventStore(root, crypto), FakeScheduler(), FakeNotifier(), FakeSettings(), ReminderRefreshSignal(), { now }, { TimeZone.UTC }),
        FakeNotifier(), ReminderRefreshSignal(), { 30 }, { true }, { now }, { "id" },
    )
    private val rows = CaptureRows()
    private val drain = CaptureDrain(queue, notes, backend, publisher, rows, { CaptureSettings() })
    private var runs = 0
    private var running: String? = null
    private val answers = UserAnswers(queue, routing, drain, processing = { it == running }) { runs++ }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun park(id: String, confirm: Boolean, asks: Set<Need>) {
        queue.enqueue(QueuedCapture(id, "$id.wav", now), byteArrayOf(1))
        val waiting = AwaitingUser(hook.ref, "Hook", "buy milk", "2026-09-22T17:00:00+02:00", confirm = confirm, asks = asks)
        queue.update { awaitUser(it, id, waiting, 0) }
    }

    private fun awaiting(id: String) = queue.readQueue(strict = true).single { it.id == id }.awaiting!!

    @Test
    fun anAnswerIsRecordedAndTheLastOneResumesACaptureThatNeedsNoConfirmation() = runBlocking<Unit> {
        routing.write(RoutingConfig(entry = hook.ref, routers = listOf(hook)))
        park("p1", confirm = false, asks = setOf(Need.LOCATION, Need.RECORDED_AT))
        assertEquals(UserAnswers.Answered.TAKEN, answers.answer("p1", Need.LOCATION, allow = true, remember = false))
        assertEquals(setOf(Need.RECORDED_AT), awaiting("p1").asks)
        assertEquals(setOf(Need.LOCATION), awaiting("p1").granted)
        assertEquals(0, runs, "a question is still open")
        assertEquals(Shares(Grant.ASK, Grant.ASK), (routing.read().router(hook.ref) as RouterConfig.Network).shares, "nothing kept unless asked")
        assertEquals(UserAnswers.Answered.TAKEN, answers.answer("p1", Need.RECORDED_AT, allow = false, remember = false))
        assertTrue(awaiting("p1").settled)
        assertEquals(1, runs, "the last answer asks for the run that resumes it")
    }

    @Test
    fun anAnswerToKeepBecomesTheRoutersGrantAndAConfirmationStillWaits() = runBlocking<Unit> {
        routing.write(RoutingConfig(entry = hook.ref, routers = listOf(hook)))
        park("p1", confirm = true, asks = setOf(Need.LOCATION))
        assertEquals(UserAnswers.Answered.TAKEN, answers.answer("p1", Need.LOCATION, allow = false, remember = true))
        assertEquals(Shares(location = Grant.NEVER, recordedAt = Grant.ASK), (routing.read().router(hook.ref) as RouterConfig.Network).shares)
        assertEquals(emptySet(), awaiting("p1").asks)
        assertFalse(awaiting("p1").settled)
        assertEquals(0, runs, "the confirmation is still wanted")
        answers.confirm("p1")
        assertTrue(awaiting("p1").settled)
        assertEquals(1, runs)
        park("p2", confirm = false, asks = setOf(Need.RECORDED_AT))
        assertEquals(UserAnswers.Answered.TAKEN, answers.answer("p2", Need.RECORDED_AT, allow = true, remember = true))
        assertEquals(Shares(location = Grant.NEVER, recordedAt = Grant.ALWAYS), (routing.read().router(hook.ref) as RouterConfig.Network).shares)
    }

    /** The answer is for this recording whatever happens to the routing; the router that is gone cannot keep it. */
    @Test
    fun anAnswerIsTakenEvenWhenItCannotBeKeptAndNothingIsTakenForACaptureThatIsNotWaiting() = runBlocking<Unit> {
        park("p1", confirm = false, asks = setOf(Need.LOCATION))
        assertEquals(UserAnswers.Answered.GRANT_NOT_KEPT, answers.answer("p1", Need.LOCATION, allow = true, remember = true))
        assertEquals(setOf(Need.LOCATION), awaiting("p1").granted)
        assertEquals(1, runs)
        assertEquals(UserAnswers.Answered.NOT_WAITING, answers.answer("zz", Need.LOCATION, allow = true, remember = false))
        queue.enqueue(QueuedCapture("plain", "plain.wav", now), byteArrayOf(1))
        assertEquals(UserAnswers.Answered.NOT_WAITING, answers.answer("plain", Need.LOCATION, allow = true, remember = false))
        assertNull(queue.readQueue(strict = true).single { it.id == "plain" }.awaiting)
    }

    @Test
    fun aRefusalIsRecordedAndARouterSetToAskFirstMeanwhileGetsItsConfirmation() = runBlocking<Unit> {
        routing.write(RoutingConfig(entry = hook.ref, routers = listOf(hook)))
        park("p1", confirm = false, asks = setOf(Need.LOCATION))
        routing.write(RoutingConfig(entry = hook.ref, routers = listOf(hook.copy(confirm = true))))
        assertEquals(UserAnswers.Answered.TAKEN, answers.answer("p1", Need.LOCATION, allow = false, remember = false))
        assertEquals(setOf(Need.LOCATION), awaiting("p1").denied)
        assertTrue(awaiting("p1").confirm, "the router asks first now, so the capture waits for the Send")
        assertFalse(awaiting("p1").settled)
        assertEquals(0, runs)
        answers.confirm("p1")
        assertEquals(1, runs)
    }

    @Test
    fun aDismissDropsTheWaitingCaptureAndItsRecordingButNotOneTheDrainHas() = runBlocking<Unit> {
        park("p1", confirm = true, asks = emptySet())
        assertEquals(QueueStore.AwaitingRemoval.REMOVED, answers.dismiss("p1"))
        assertTrue(queue.readQueue(strict = true).isEmpty())
        assertFalse(queue.hasBlob("p1"))
        assertEquals(QueueStore.AwaitingRemoval.GONE, answers.dismiss("p1"))
        park("p2", confirm = true, asks = emptySet())
        running = "p2"
        assertEquals(QueueStore.AwaitingRemoval.NOT_AWAITING, answers.dismiss("p2"))
        assertTrue(queue.hasBlob("p2"))
    }
}
