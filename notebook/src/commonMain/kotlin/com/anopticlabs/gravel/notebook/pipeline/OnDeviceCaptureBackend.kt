package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.llm.PROMPT_CONTENT_BUDGET
import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.llm.capToBudget
import com.anopticlabs.gravel.notebook.notes.NoteBuilder
import com.anopticlabs.gravel.notebook.reminders.ReminderExtractor
import com.anopticlabs.gravel.notebook.reminders.SpokenTimeReader
import com.anopticlabs.gravel.notebook.reminders.effectiveTriggerEnabled
import com.anopticlabs.gravel.notebook.reminders.shouldParkTriggerCompletion
import com.anopticlabs.gravel.notebook.routers.RouteWalk
import com.anopticlabs.gravel.notebook.routers.RoutedText
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.toLocalDateTime

/** The drain's held reason when memory stays short after the text model is freed. */
const val SHORT_OF_MEMORY = "too little free memory to transcribe; a smaller speech model needs less"

/** Reply tokens for the note and reminder JSON, whose answers run under a hundred tokens. */
const val REPLY_TOKENS = 512

/** The segments' texts as one transcript; whisper starts each segment's text with its own space. */
fun joinTranscript(segmentTexts: List<String>): String = segmentTexts.joinToString("").trim()

private val NON_SPEECH_TOKENS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")

/** False for a transcript with fewer than two letters or digits outside bracketed non-speech tokens. */
fun hasSpeech(transcript: String): Boolean =
    transcript.replace(NON_SPEECH_TOKENS, "").count { it.isLetterOrDigit() } >= 2

/**
 * The capture pipeline: decode, transcribe, then a completion claim is parked without the text
 * model; otherwise the transcript goes through the text routing ([routing], read for each capture)
 * to the router that consumes it: a note or a reminder built here with the text model, or a
 * [BuiltCapture.Forward] for the drain to deliver. Before a transcription on the phone, the text
 * model is freed when memory is short ([SpeechCapture.phoneFits]).
 */
class OnDeviceCaptureBackend(
    private val decoder: CaptureAudioDecoder,
    private val speech: CaptureSpeech,
    private val text: CaptureText,
    private val memory: CaptureMemory,
    private val routing: () -> RoutingConfig = { RoutingConfig.DEFAULT },
) : CaptureBackend {

    override suspend fun checkReady() {
        readRouting()
        speech.checkReady()
        text.checkReady()
    }

    override suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture {
        onStage(CaptureStage.TRANSCRIBING)
        val pcm16 = decoder.decode(request.audio)
        val capture = SpeechCapture(
            request.id, request.phoneTried, ::phoneFits, request.markPhoneTried,
            request.long, request.fromWatch, request.serverTries, request.serverTriedAtMillis, request.markServerTry,
        )
        val transcript = speech.transcribe(pcm16, capture)
        if (!hasSpeech(transcript)) throw CaptureProcessingException(CaptureFailureKind.FLAG, "no speech was heard", reason = CaptureFailureReason.NO_SPEECH)
        val settings = request.settings
        val triggerOn = effectiveTriggerEnabled(settings.triggerEnabled, settings.triggerWord)
        // A completion claim is a command to the Notebook, decided before the routing sees the text.
        shouldParkTriggerCompletion(triggerOn, settings.triggerWord, transcript)?.let { utterance ->
            onStage(CaptureStage.COMPLETING_TASK)
            return BuiltCapture.Completion(utterance)
        }
        val config = readRouting()
        val routed = RoutedText.of(request.id, request.capturedAt, request.zone, transcript)
        return when (val terminal = RouteWalk.terminal(config, routed)) {
            is RouterConfig.Note -> buildNote(request, routed, onStage)
            is RouterConfig.Reminder -> buildReminder(request, routed, onStage)
            is RouterConfig.App, is RouterConfig.Network -> {
                onStage(CaptureStage.FORWARDING)
                BuiltCapture.Forward(terminal, routed)
            }
            is RouterConfig.Switch -> throw IllegalStateException("the routing walk ended at a switch")
        }
    }

    private suspend fun buildNote(request: CaptureRequest, routed: RoutedText, onStage: (CaptureStage) -> Unit): BuiltCapture.Note {
        onStage(CaptureStage.THINKING)
        text.prepare()
        onStage(CaptureStage.CREATING_NOTE)
        val raw = text.generate(Prompts.NOTE_SYSTEM, Prompts.notePromptVerbatim(capToBudget(routed.text, PROMPT_CONTENT_BUDGET)), REPLY_TOKENS)
        val note = NoteBuilder.buildNote(
            rawReply = raw,
            transcript = routed.text,
            folders = request.folders,
            folderTags = request.folderTags,
            now = request.capturedAt.toLocalDateTime(request.zone),
            recordedAt = routed.recordedAt,
        )
        return BuiltCapture.Note(note)
    }

    private suspend fun buildReminder(request: CaptureRequest, routed: RoutedText, onStage: (CaptureStage) -> Unit): BuiltCapture.Reminder {
        onStage(CaptureStage.THINKING)
        text.prepare()
        onStage(CaptureStage.SETTING_REMINDER)
        // The model writes the message only; the rules read the time from the whole transcript.
        val raw = text.generate(Prompts.REMINDER_MESSAGE_SYSTEM, Prompts.reminderMessagePrompt(capToBudget(routed.text, PROMPT_CONTENT_BUDGET)), REPLY_TOKENS)
        val spoken = SpokenTimeReader.read(routed.text, request.capturedAt, request.zone)
        return BuiltCapture.Reminder(ReminderExtractor.proposal(raw, routed.text, spoken, routed.recordedAt))
    }

    // Every capture would go the same way without its routing, so a routing that cannot be read holds the drain.
    private fun readRouting(): RoutingConfig = try {
        routing()
    } catch (e: CaptureProcessingException) {
        throw e
    } catch (e: Exception) {
        throw CaptureProcessingException(CaptureFailureKind.HOLD, "the text routing settings could not be read", e, remedy = CaptureRemedy.ROUTING_SETTINGS)
    }

    private suspend fun phoneFits(): Boolean {
        if (!memory.shortForSpeech()) return true
        try {
            text.release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A model left loaded keeps memory short, which the check below reads.
            logger.w { "text model not freed: ${e::class.simpleName}" }
        }
        return !memory.shortForSpeech()
    }

    private companion object {
        val logger = Logger.withTag("OnDeviceCaptureBackend")
    }
}
