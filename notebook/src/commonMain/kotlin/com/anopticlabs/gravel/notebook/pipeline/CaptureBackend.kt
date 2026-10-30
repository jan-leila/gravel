package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import com.anopticlabs.gravel.notebook.routers.RoutedText
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** What a capture asked for, ready to publish. */
sealed interface BuiltCapture {
    data class Note(val note: BuiltNote) : BuiltCapture

    data class Reminder(val proposal: ReminderProposal) : BuiltCapture

    /** A completion claim: what was said after the trigger word, possibly nothing. */
    data class Completion(val utterance: String) : BuiltCapture

    /** The text routing ended at [router], an app or a server, which the drain delivers [text] to with the parts in [share]. */
    data class Forward(val router: RouterConfig, val text: RoutedText, val share: Set<Need> = emptySet()) : BuiltCapture
}

data class CaptureSettings(
    val triggerEnabled: Boolean = false,
    val triggerWord: String = "",
)

/**
 * One queued recording to process. [capturedAt] is when the recording started, on the phone or on
 * the watch, never when it was queued or processed: it dates the note, stamps what is built from
 * the transcript, and is the "now" the reminder's time is read against; [location] is where it was made, when known.
 * [folders] are the ones a note may be routed to.
 * [phoneTried], [markPhoneTried], [long], [fromWatch], [serverTries], [serverTriedAtMillis] and [markServerTry] are what
 * the entry gives its [SpeechCapture].
 */
class CaptureRequest(
    val audio: ByteArray,
    val capturedAt: Instant,
    val zone: TimeZone,
    val folders: List<String>,
    val folderTags: Map<String, List<String>>,
    val settings: CaptureSettings,
    val id: String = "",
    val phoneTried: Boolean = false,
    val markPhoneTried: suspend () -> Unit = {},
    val long: Boolean = false,
    val fromWatch: Boolean = false,
    val serverTries: Int = 0,
    val serverTriedAtMillis: Long? = null,
    val markServerTry: suspend (atMillis: Long) -> Boolean = { false },
    val location: RoutedLocation? = null,
)

/** Turns a recording into what it asked for; failures as [CaptureProcessingException]. */
interface CaptureBackend {
    /** Fails with [CaptureFailureKind.HOLD] while a capture could not be processed at all. */
    suspend fun checkReady()

    suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture
}
