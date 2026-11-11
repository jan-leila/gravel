package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.llm.PROMPT_CONTENT_BUDGET
import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.reminders.ReminderTimeStatus
import com.anopticlabs.gravel.notebook.routers.Grant
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import com.anopticlabs.gravel.notebook.routers.RoutedText
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RouterRef
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.Shares
import com.anopticlabs.gravel.notebook.routers.SwitchRule
import com.anopticlabs.gravel.notebook.storage.CorruptFileException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

class OnDeviceCaptureBackendTest {

    private class Generation(val system: String, val user: String, val maxTokens: Int)

    private class FakeText(var reply: String = """{"title": "Buy milk", "tags": ["shopping"]}""") : CaptureText {
        val generations = mutableListOf<Generation>()
        var prepared = 0
        var ready = 0
        var released = 0
        var failRelease = false
        override suspend fun checkReady() {
            ready++
        }
        override suspend fun prepare() {
            prepared++
        }
        override suspend fun release() {
            released++
            if (failRelease) throw IllegalStateException("free refused")
        }
        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
            generations += Generation(system, user, maxTokens)
            return reply
        }
    }

    /** Asks [SpeechCapture.phoneFits] first unless [askFits] is off, as a phone transcription does. */
    private class FakeSpeech(var transcript: String, val askFits: Boolean = true) : CaptureSpeech {
        var heard: ByteArray? = null
        var capture: SpeechCapture? = null
        var fits: Boolean? = null
        var ready = 0
        override suspend fun checkReady() {
            ready++
        }
        override fun route() = SpeechRoute.PHONE
        override suspend fun transcribe(pcm16: ByteArray, capture: SpeechCapture): String {
            this.capture = capture
            if (askFits) fits = capture.phoneFits()
            heard = pcm16
            return transcript
        }
    }

    private val plenty = CaptureMemory { false }

    private val decoded = byteArrayOf(1, 2, 3, 4)
    private val decoder = CaptureAudioDecoder { decoded }

    private var graph = RoutingConfig.DEFAULT

    /** Today's behavior before routing existed: a reminder cue at the start makes a reminder, else a note. */
    private val split = RoutingConfig(
        entry = RouterRef.gravel("split"),
        routers = listOf(
            RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", RouterRef.gravel("reminders"))), otherwise = RouterRef.gravel("notes")),
            RouterConfig.Note("notes", "Notes"),
            RouterConfig.Reminder("reminders", "Reminders"),
        ),
    )

    // 2026-09-22T15:00:00Z, 17:00 in Berlin.
    private val capturedAt = Instant.fromEpochMilliseconds(1_790_089_200_000L)
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun request(
        settings: CaptureSettings = CaptureSettings(),
        folders: List<String> = listOf("unclassified", "shopping"),
        folderTags: Map<String, List<String>> = mapOf("shopping" to listOf("shopping")),
        id: String = "c1",
        phoneTried: Boolean = false,
        markPhoneTried: suspend () -> Unit = {},
        location: RoutedLocation? = null,
    ) = CaptureRequest(byteArrayOf(9), capturedAt, berlin, folders, folderTags, settings, id, phoneTried, markPhoneTried, location = location)

    private suspend fun run(
        transcript: String,
        text: FakeText = FakeText(),
        settings: CaptureSettings = CaptureSettings(),
        stages: MutableList<CaptureStage> = mutableListOf(),
        location: RoutedLocation? = null,
    ): BuiltCapture {
        val speech = FakeSpeech(transcript)
        val built = OnDeviceCaptureBackend(decoder, speech, text, plenty, routing = { graph }).process(request(settings, location = location)) { stages += it }
        assertTrue(speech.heard.contentEquals(decoded), "the speech engine did not get the decoded audio")
        return built
    }

    @Test
    fun aNoteIsTitledByTheModelWithTheTranscriptAsItsBodyAndDatedByTheCapture() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        val built = assertIs<BuiltCapture.Note>(run("get milk and eggs on the way home", text, stages = stages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.CREATING_NOTE), stages)
        assertEquals("Buy milk", built.note.title)
        assertEquals("shopping", built.note.folder)
        assertEquals("2026-09-22", built.note.date)
        assertTrue(built.note.filename.endsWith("_09.22.26_1700.md"), built.note.filename)
        assertTrue(built.note.markdown.contains("get milk and eggs on the way home"))
        assertTrue(built.note.markdown.contains("\nrecorded: 2026-09-22T17:00:00+02:00\n"), built.note.markdown)
        val generation = text.generations.single()
        assertEquals(Prompts.NOTE_SYSTEM, generation.system)
        assertEquals(Prompts.notePromptVerbatim("get milk and eggs on the way home"), generation.user)
        assertEquals(REPLY_TOKENS, generation.maxTokens)
        assertEquals(1, text.prepared)
    }

    @Test
    fun theModelSeesACappedTranscriptAndTheNoteKeepsAllOfIt() = runBlocking<Unit> {
        val transcript = "word ".repeat(PROMPT_CONTENT_BUDGET / 4)
        val text = FakeText()
        val built = assertIs<BuiltCapture.Note>(run(transcript, text))
        assertTrue(text.generations.single().user.length < transcript.length)
        assertTrue(built.note.markdown.contains(transcript.trim()))
    }

    /** The rules read the time from the moment of the recording; the model's own time is not used. */
    @Test
    fun aReminderTimeIsReadByTheRulesAgainstTheTimeOfTheRecording() = runBlocking<Unit> {
        graph = split
        val text = FakeText("""{"remind_in_seconds": 3600, "message": "Check the oven"}""")
        val stages = mutableListOf<CaptureStage>()
        val built = assertIs<BuiltCapture.Reminder>(run("remind me in ten minutes to check the oven", text, stages = stages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.SETTING_REMINDER), stages)
        assertEquals(ReminderTimeStatus.USABLE, built.proposal.timeStatus)
        assertEquals(capturedAt.toEpochMilliseconds() + 600_000L, built.proposal.remindAtMillis)
        assertEquals("Check the oven", built.proposal.message)
        val generation = text.generations.single()
        assertEquals(Prompts.REMINDER_MESSAGE_SYSTEM, generation.system)
        assertEquals(Prompts.reminderMessagePrompt("remind me in ten minutes to check the oven"), generation.user)
        assertEquals(REPLY_TOKENS, generation.maxTokens)
    }

    /** A clock time is read in the recording's zone, from the whole transcript rather than the copy capped for the model. */
    @Test
    fun aClockTimeIsReadInTheRecordingsZoneFromTheWholeTranscript() = runBlocking<Unit> {
        graph = split
        val text = FakeText("""{"message": "Call Sam"}""")
        val transcript = "remind me to call Sam " + "um ".repeat(PROMPT_CONTENT_BUDGET / 2) + "tomorrow at 9"
        val built = assertIs<BuiltCapture.Reminder>(run(transcript, text))
        assertTrue(text.generations.single().user.length < transcript.length)
        assertEquals(Instant.parse("2026-09-23T07:00:00Z").toEpochMilliseconds(), built.proposal.remindAtMillis)
    }

    @Test
    fun aTimeTheRulesCannotReadIsToCheck() = runBlocking<Unit> {
        graph = split
        val text = FakeText("""{"remind_in_seconds": 600, "message": "Call Sam"}""")
        val built = assertIs<BuiltCapture.Reminder>(run("remind me to call Sam after lunch", text))
        assertEquals(ReminderProposal("Call Sam", null, ReminderTimeStatus.TO_CHECK, recordedAt = "2026-09-22T17:00:00+02:00"), built.proposal)
    }

    /** The claim is decided before the text model, which a completion never needs. */
    @Test
    fun aTriggerWordCaptureIsParkedWithoutTheTextModel() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        val built = run("Done, fed the cat", text, CaptureSettings(triggerEnabled = true, triggerWord = "done"), stages)
        assertEquals(BuiltCapture.Completion("fed the cat"), built)
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.COMPLETING_TASK), stages)
        assertTrue(text.generations.isEmpty())
        assertEquals(0, text.prepared)
    }

    @Test
    fun aTriggerWordBeatsAReminderCue() = runBlocking<Unit> {
        val built = run("done remind me to call mom", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "done"))
        assertIs<BuiltCapture.Completion>(built)
    }

    @Test
    fun theTriggerWordAloneParksAnEmptyClaim() = runBlocking<Unit> {
        assertEquals(BuiltCapture.Completion(""), run("Done.", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "done")))
    }

    @Test
    fun withTheTriggerOnButNotSaidTheNoteIsWritten() = runBlocking<Unit> {
        assertIs<BuiltCapture.Note>(run("get milk", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "done")))
    }

    @Test
    fun aStoredTriggerWordThatNoLongerValidatesIsOff() = runBlocking<Unit> {
        assertIs<BuiltCapture.Note>(run("note to self get milk", FakeText(), CaptureSettings(triggerEnabled = true, triggerWord = "note")))
        assertIs<BuiltCapture.Note>(run("done get milk", FakeText(), CaptureSettings(triggerEnabled = false, triggerWord = "done")))
    }

    @Test
    fun aRecordingWithNoSpeechIsFlaggedBeforeTheTextModel() = runBlocking<Unit> {
        for (heard in listOf("", "  ", "[BLANK_AUDIO]", "(wind blowing) [Music]", "a")) {
            val text = FakeText()
            val failure = assertFailsWith<CaptureProcessingException>(heard) { run(heard, text) }
            assertEquals(CaptureFailureKind.FLAG, failure.kind)
            assertEquals(CaptureFailureReason.NO_SPEECH, failure.reason)
            assertTrue(text.generations.isEmpty())
            assertEquals(0, text.prepared)
        }
    }

    @Test
    fun speechIsTwoLettersOrDigitsOutsideNonSpeechTokens() {
        assertTrue(hasSpeech("ok"))
        assertTrue(hasSpeech("[Music] call 42"))
        assertFalse(hasSpeech("(laughs) a"))
        assertFalse(hasSpeech("..."))
    }

    @Test
    fun segmentTextsJoinAsWhisperSpacedThem() {
        assertEquals("Hello there. General Kenobi.", joinTranscript(listOf(" Hello there.", " General Kenobi.", " ")))
    }

    @Test
    fun readinessAsksBothEngines() = runBlocking<Unit> {
        val speech = FakeSpeech("x")
        val text = FakeText()
        OnDeviceCaptureBackend(decoder, speech, text, plenty).checkReady()
        assertEquals(1, speech.ready)
        assertEquals(1, text.ready)
    }

    @Test
    fun theTextModelStaysLoadedWhileMemoryIsNotShort() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, speech, text, plenty).process(request()) {}
        assertEquals(0, text.released)
        assertEquals(true, speech.fits)
    }

    @Test
    fun shortMemoryFreesTheTextModelAndChecksAgain() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk")
        val built = OnDeviceCaptureBackend(decoder, speech, text, CaptureMemory { text.released == 0 }).process(request()) {}
        assertIs<BuiltCapture.Note>(built)
        assertEquals(1, text.released)
        assertEquals(true, speech.fits)
    }

    @Test
    fun aTextModelThatCannotBeFreedLeavesTheDecisionToTheSecondCheck() = runBlocking<Unit> {
        val text = FakeText().apply { failRelease = true }
        var checks = 0
        val fitting = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, fitting, text, CaptureMemory { checks++ == 0 }).process(request()) {}
        assertEquals(true, fitting.fits)
        val short = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, short, text, CaptureMemory { true }).process(request()) {}
        assertEquals(false, short.fits)
    }

    @Test
    fun memoryStillShortAfterFreeingTheTextModelReadsAsNotFitting() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, speech, text, CaptureMemory { true }).process(request()) {}
        assertEquals(false, speech.fits)
        assertEquals(1, text.released)
    }

    @Test
    fun memoryIsReadOnlyWhenTheSpeechStepAsks() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("get milk", askFits = false)
        val built = OnDeviceCaptureBackend(decoder, speech, text, CaptureMemory { error("memory read") }).process(request()) {}
        assertIs<BuiltCapture.Note>(built)
        assertEquals(0, text.released)
    }

    @Test
    fun theEntrysMarkerReachesTheSpeechStep() = runBlocking<Unit> {
        var marked = 0
        val speech = FakeSpeech("get milk")
        OnDeviceCaptureBackend(decoder, speech, FakeText(), plenty).process(request(id = "c7", phoneTried = true, markPhoneTried = { marked++ })) {}
        val capture = assertNotNull(speech.capture)
        assertEquals("c7", capture.id)
        assertTrue(capture.phoneTried)
        capture.markPhoneTried()
        assertEquals(1, marked)
    }

    private fun CaptureRequest.copyOrigin(long: Boolean, fromWearable: Boolean) = CaptureRequest(
        audio, capturedAt, zone, folders, folderTags, settings, id, phoneTried, markPhoneTried, long, fromWearable, serverTries, serverTriedAtMillis, markServerTry,
    )

    @Test
    fun theEntrysServerTriesAndOriginReachTheSpeechStep() = runBlocking<Unit> {
        var markedAt: Long? = null
        val speech = FakeSpeech("get milk")
        val request = CaptureRequest(
            byteArrayOf(9), capturedAt, berlin, listOf("unclassified"), emptyMap(), CaptureSettings(), "c8",
            long = true, fromWearable = false, serverTries = 2, serverTriedAtMillis = 40L, markServerTry = { markedAt = it; true },
        )
        OnDeviceCaptureBackend(decoder, speech, FakeText(), plenty).process(request) {}
        val capture = assertNotNull(speech.capture)
        assertTrue(capture.long)
        assertFalse(capture.fromWearable)
        OnDeviceCaptureBackend(decoder, speech, FakeText(), plenty).process(request.copyOrigin(long = false, fromWearable = true)) {}
        assertFalse(assertNotNull(speech.capture).long)
        assertTrue(assertNotNull(speech.capture).fromWearable)
        assertEquals(2, capture.serverTries)
        assertEquals(40L, capture.serverTriedAtMillis)
        assertTrue(capture.markServerTry(77L))
        assertEquals(77L, markedAt)
    }

    @Test
    fun memoryIsShortWhenTheNeedTakesTheAvailableUnderTheThreshold() {
        assertFalse(memoryShortFor(needBytes = 100, availableBytes = 1_000, thresholdBytes = 900))
        assertTrue(memoryShortFor(needBytes = 101, availableBytes = 1_000, thresholdBytes = 900))
        assertTrue(memoryShortFor(needBytes = 0, availableBytes = 899, thresholdBytes = 900))
    }

    private val tasks = RouterConfig.App("tasks", "Tasks", "com.example.tasks", "com.example.tasks.Router", "a".repeat(64))
    private val hook = RouterConfig.Network("hook", "Webhook", "https://example.test/notes")

    @Test
    fun theDefaultRoutingWritesATranscriptAsANoteUnlessItOpensWithAReminderCue() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        val built = assertIs<BuiltCapture.Note>(run("in ten minutes I should check the oven", text, stages = stages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.CREATING_NOTE), stages)
        assertEquals(Prompts.NOTE_SYSTEM, text.generations.single().system)
        assertTrue(built.note.markdown.contains("in ten minutes I should check the oven"))
        val reminderText = FakeText("""{"message": "Check the oven"}""")
        val reminderStages = mutableListOf<CaptureStage>()
        val reminder = assertIs<BuiltCapture.Reminder>(run("remind me in ten minutes to check the oven", reminderText, stages = reminderStages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.SETTING_REMINDER), reminderStages)
        assertEquals(Prompts.REMINDER_MESSAGE_SYSTEM, reminderText.generations.single().system)
        assertEquals("Check the oven", reminder.proposal.message)
    }

    @Test
    fun aReminderRouterSetsAReminderWhateverTheWordsSay() = runBlocking<Unit> {
        graph = RoutingConfig(entry = RouterRef.gravel("r"), routers = listOf(RouterConfig.Reminder("r", "Always a reminder")))
        val text = FakeText("""{"message": "Water the garden"}""")
        val stages = mutableListOf<CaptureStage>()
        val built = assertIs<BuiltCapture.Reminder>(run("thoughts on the garden", text, stages = stages))
        assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.THINKING, CaptureStage.SETTING_REMINDER), stages)
        assertEquals(ReminderProposal("Water the garden", null, ReminderTimeStatus.NO_TIME, recordedAt = "2026-09-22T17:00:00+02:00"), built.proposal)
        assertEquals(Prompts.REMINDER_MESSAGE_SYSTEM, text.generations.single().system)
    }

    /** What goes with the text is what the router needs and the user granted; an Ask grant shares nothing on its own. */
    @Test
    fun aForwardSharesTheNeededPartsTheUserGrantedAlways() = runBlocking<Unit> {
        suspend fun to(router: RouterConfig): BuiltCapture {
            graph = RoutingConfig(entry = router.ref, routers = listOf(router))
            return run("hello")
        }
        suspend fun shareTo(router: RouterConfig): Set<Need> = assertIs<BuiltCapture.Forward>(to(router)).share
        suspend fun askedBy(router: RouterConfig): Set<Need> = assertIs<BuiltCapture.ToConfirm>(to(router)).asks
        val always = Shares(location = Grant.ALWAYS, recordedAt = Grant.ALWAYS)
        assertEquals(setOf(Need.LOCATION, Need.RECORDED_AT), shareTo(hook.copy(shares = always)))
        assertEquals(setOf(Need.RECORDED_AT), shareTo(hook.copy(shares = Shares(location = Grant.NEVER, recordedAt = Grant.ALWAYS))))
        assertEquals(emptySet(), shareTo(hook.copy(shares = Shares(location = Grant.NEVER, recordedAt = Grant.NEVER))))
        assertEquals(setOf(Need.LOCATION), askedBy(hook.copy(shares = Shares(location = Grant.ASK, recordedAt = Grant.NEVER))), "an Ask pauses for the user")
        assertEquals(emptySet(), shareTo(tasks.copy(shares = always)), "an app router gets only what it declared")
        assertEquals(emptySet(), shareTo(tasks), "nothing declared, nothing to ask")
        assertEquals(setOf(Need.LOCATION), shareTo(tasks.copy(needs = setOf(Need.LOCATION), shares = always)))
        assertEquals(setOf(Need.LOCATION), askedBy(tasks.copy(needs = setOf(Need.LOCATION))))
    }

    /** What the router API promises: the transcript whole, the recording's start and place, and no text model run. */
    @Test
    fun anAppOrNetworkRouterGetsTheTranscriptWholeWithTheRecordingTimeAndPlaceAndNoModelRuns() = runBlocking<Unit> {
        val here = RoutedLocation(52.52, 13.405, 30f)
        for (router in listOf(tasks, hook.copy(shares = Shares(Grant.NEVER, Grant.NEVER)))) {
            graph = RoutingConfig(entry = router.ref, routers = listOf(router))
            val text = FakeText()
            val stages = mutableListOf<CaptureStage>()
            val transcript = "  remind me: the whole text, [noise] as heard  "
            val built = assertIs<BuiltCapture.Forward>(run(transcript, text, stages = stages, location = here))
            assertEquals(router, built.router)
            assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", transcript, here), built.text)
            assertEquals(null, assertIs<BuiltCapture.Forward>(run(transcript)).text.location)
            assertEquals(emptySet(), built.share, "nothing shared without a grant")
            assertEquals(listOf(CaptureStage.TRANSCRIBING, CaptureStage.FORWARDING), stages)
            assertEquals(0, text.prepared)
            assertTrue(text.generations.isEmpty())
        }
    }

    @Test
    fun aSwitchRoutesByItsRulesAndTheCompletionClaimIsDecidedBeforeIt() = runBlocking<Unit> {
        graph = RoutingConfig(
            entry = RouterRef.gravel("split"),
            routers = listOf(RouterConfig.Switch("split", "Split", listOf(SwitchRule("garden", tasks.ref)), otherwise = RouterRef.gravel("notes")), RouterConfig.Note("notes", "Notes"), tasks),
        )
        assertIs<BuiltCapture.Forward>(run("thoughts on the garden"))
        assertIs<BuiltCapture.Note>(run("thoughts on the kitchen"))
        val claim = run("Done, weeded the garden", settings = CaptureSettings(triggerEnabled = true, triggerWord = "done"))
        assertEquals(BuiltCapture.Completion("weeded the garden"), claim)
    }

    private fun resumeOf(router: RouterConfig, text: String, granted: Set<Need> = emptySet(), location: RoutedLocation? = null, denied: Set<Need> = emptySet()) = CaptureResume(
        router.ref, router.name, RoutedText("c1", "2026-09-22T17:00:00+02:00", text, location), granted,
        capturedAt, berlin, listOf("unclassified", "shopping"), mapOf("shopping" to listOf("shopping")), denied,
    )

    private suspend fun resume(resume: CaptureResume, text: FakeText = FakeText(), stages: MutableList<CaptureStage> = mutableListOf()): BuiltCapture {
        val speech = FakeSpeech("never heard")
        val built = OnDeviceCaptureBackend(decoder, speech, text, plenty, routing = { graph }).resume(resume) { stages += it }
        assertNull(speech.heard, "a resume transcribes nothing")
        return built
    }

    /** A router that asks the user gets nothing built: the answer may change what is sent, or drop the recording. */
    @Test
    fun aRouterThatAsksTheUserParksTheCaptureWithNothingBuilt() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        graph = RoutingConfig(entry = hook.ref, routers = listOf(hook))
        val asked = assertIs<BuiltCapture.ToConfirm>(run("hello", text, stages = stages))
        assertEquals(hook, asked.router)
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello"), asked.text)
        assertEquals(setOf(Need.LOCATION, Need.RECORDED_AT), asked.asks)
        assertEquals(listOf(CaptureStage.TRANSCRIBING), stages)
        assertEquals(0, text.prepared)
        assertTrue(text.generations.isEmpty())
        val careful = RouterConfig.Note("notes", "Notes", confirm = true)
        graph = RoutingConfig(entry = careful.ref, routers = listOf(careful))
        assertEquals(BuiltCapture.ToConfirm(careful, RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello"), emptySet()), run("hello", text))
        assertTrue(text.generations.isEmpty())
        val claim = run("Done, weeded the garden", settings = CaptureSettings(triggerEnabled = true, triggerWord = "done"))
        assertEquals(BuiltCapture.Completion("weeded the garden"), claim, "a completion claim is decided before the routing, so it never waits")
    }

    /** The kept transcript goes through the same prompts as a fresh one; the grants given are the ones a delivery carries. */
    @Test
    fun aResumeBuildsFromTheKeptTranscriptWithTheGrantsGiven() = runBlocking<Unit> {
        val text = FakeText()
        val stages = mutableListOf<CaptureStage>()
        val careful = RouterConfig.Note("notes", "Notes", confirm = true)
        graph = RoutingConfig(entry = careful.ref, routers = listOf(careful))
        val note = assertIs<BuiltCapture.Note>(resume(resumeOf(careful, "get milk and eggs on the way home"), text, stages))
        assertEquals(listOf(CaptureStage.THINKING, CaptureStage.CREATING_NOTE), stages)
        assertEquals("Buy milk", note.note.title)
        assertEquals("shopping", note.note.folder)
        assertTrue(note.note.markdown.contains("\nrecorded: 2026-09-22T17:00:00+02:00\n"), note.note.markdown)
        assertEquals(Prompts.notePromptVerbatim("get milk and eggs on the way home"), text.generations.single().user)

        graph = split
        val reminders = split.router(RouterRef.gravel("reminders"))!!
        val reminder = assertIs<BuiltCapture.Reminder>(resume(resumeOf(reminders, "remind me in ten minutes to check the oven"), FakeText("""{"message": "Check the oven"}""")))
        assertEquals(capturedAt.toEpochMilliseconds() + 600_000L, reminder.proposal.remindAtMillis)
        assertEquals("2026-09-22T17:00:00+02:00", reminder.proposal.recordedAt)

        graph = RoutingConfig(entry = hook.ref, routers = listOf(hook))
        val here = RoutedLocation(52.52, 13.405)
        val forward = assertIs<BuiltCapture.Forward>(resume(resumeOf(hook, "hello", granted = setOf(Need.LOCATION), location = here)))
        assertEquals(hook, forward.router)
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello", here), forward.text)
        assertEquals(setOf(Need.LOCATION), forward.share, "a one-time grant answers the ask; the unanswered one stays withheld")
        val always = hook.copy(shares = Shares(Grant.ALWAYS, Grant.ALWAYS))
        graph = RoutingConfig(entry = always.ref, routers = listOf(always))
        val refused = assertIs<BuiltCapture.Forward>(resume(resumeOf(hook, "hello", location = here, denied = setOf(Need.LOCATION))))
        assertEquals(setOf(Need.RECORDED_AT), refused.share, "a No for this recording holds against an Always the router gained meanwhile")
        val renamed = hook.copy(name = "Hook 2", url = "https://example.test/v2")
        graph = RoutingConfig(entry = renamed.ref, routers = listOf(renamed))
        assertEquals(renamed, assertIs<BuiltCapture.Forward>(resume(resumeOf(hook, "hello"))).router, "the router as it is now, not as it was")
    }

    @Test
    fun aResumeToARouterThatIsGoneOrNowForwardsIsFlaggedAndAnUnreadableRoutingHolds() = runBlocking<Unit> {
        graph = RoutingConfig.DEFAULT
        val gone = assertFailsWith<CaptureProcessingException> { resume(resumeOf(hook, "hello")) }
        assertEquals(CaptureFailureKind.FLAG, gone.kind)
        assertEquals(CaptureFailureReason.ROUTER_UNAVAILABLE, gone.reason)
        assertEquals("Webhook no longer exists in the text routing", gone.message)
        graph = split
        val switch = split.router(RouterRef.gravel("split"))!!
        val forwards = assertFailsWith<CaptureProcessingException> { resume(resumeOf(switch, "hello")) }
        assertEquals(CaptureFailureKind.FLAG, forwards.kind)
        assertEquals("Split now forwards elsewhere", forwards.message)
        val backend = OnDeviceCaptureBackend(decoder, FakeSpeech("x"), FakeText(), plenty, routing = { throw CorruptFileException("routing", IOException()) })
        val held = assertFailsWith<CaptureProcessingException> { backend.resume(resumeOf(hook, "hello")) {} }
        assertEquals(CaptureFailureKind.HOLD, held.kind)
    }

    @Test
    fun aRoutingThatCannotBeReadHoldsBeforeAnyEngineRuns() = runBlocking<Unit> {
        val text = FakeText()
        val speech = FakeSpeech("anything at all")
        val backend = OnDeviceCaptureBackend(decoder, speech, text, plenty, routing = { throw CorruptFileException("routing", IOException()) })
        val ready = assertFailsWith<CaptureProcessingException> { backend.checkReady() }
        assertEquals(CaptureFailureKind.HOLD, ready.kind)
        assertEquals("the text routing settings could not be read", ready.message)
        assertEquals(0, text.ready)
        val processed = assertFailsWith<CaptureProcessingException> { backend.process(request()) {} }
        assertEquals(CaptureFailureKind.HOLD, processed.kind)
        assertEquals(0, text.prepared)
    }
}
