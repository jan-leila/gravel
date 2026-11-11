package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import com.anopticlabs.gravel.notebook.routers.RoutedText
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RouterRef
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

    /**
     * The text routing ended at [router], which waits for the user: a confirmation, questions about
     * the needs in [asks], or both. Nothing was built; the drain parks [text] and resumes on the answer.
     */
    data class ToConfirm(val router: RouterConfig, val text: RoutedText, val asks: Set<Need>) : BuiltCapture
}

data class CaptureSettings(
    val triggerEnabled: Boolean = false,
    val triggerWord: String = "",
)

/**
 * One queued recording to process. [capturedAt] is when the recording started, on the phone, the
 * watch or the ring, never when it was queued or processed: it dates the note, stamps what is built from
 * the transcript, and is the "now" the reminder's time is read against; [location] is where it was made, when known.
 * [folders] are the ones a note may be routed to.
 * [phoneTried], [markPhoneTried], [long], [fromWearable], [serverTries], [serverTriedAtMillis] and [markServerTry] are what
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
    val fromWearable: Boolean = false,
    val serverTries: Int = 0,
    val serverTriedAtMillis: Long? = null,
    val markServerTry: suspend (atMillis: Long) -> Boolean = { false },
    val location: RoutedLocation? = null,
)

/**
 * A parked capture to take up again: the [text] as it was routed, the [router] the walk ended at
 * (its current definition is used; [routerName] is for a failure that finds it gone), the needs the
 * user [granted] and [denied] for this recording, and what the Note and Reminder builders read off a request.
 */
class CaptureResume(
    val router: RouterRef,
    val routerName: String,
    val text: RoutedText,
    val granted: Set<Need>,
    val capturedAt: Instant,
    val zone: TimeZone,
    val folders: List<String>,
    val folderTags: Map<String, List<String>>,
    val denied: Set<Need> = emptySet(),
)

/** Turns a recording into what it asked for; failures as [CaptureProcessingException]. */
interface CaptureBackend {
    /** Fails with [CaptureFailureKind.HOLD] while a capture could not be processed at all. */
    suspend fun checkReady()

    suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture

    /** Finishes a parked capture from its kept transcript: no audio, no speech step; never a [BuiltCapture.ToConfirm]. */
    suspend fun resume(resume: CaptureResume, onStage: (CaptureStage) -> Unit): BuiltCapture
}
