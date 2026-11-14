package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import com.anopticlabs.gravel.notebook.routers.RoutedText
import com.anopticlabs.gravel.notebook.routers.RouterRef
import com.anopticlabs.gravel.notebook.routers.TextMatch
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/**
 * The durable record of a captured recording, written before any processing; its audio blob is
 * keyed by [id]. [zoneId] is the time zone the recording was made in, see [captureZone];
 * [durationMillis] its length when known, see [isLong]; [failure] the code of why it was flagged, see [failureReason];
 * [phoneTried] that the phone's speech step already failed it under a phone-then-server route; [serverTries] how
 * often the server could not be reached for it under a server-then-phone route, the last at [serverTriedAtMillis];
 * [fromWatch] and [fromRing] where it was recorded.
 * [latitude], [longitude] and [accuracyMeters] are where it was made when the phone knew, see [location].
 * [openApp] is the UUID of the watch app open on the watch when it was taken in, when one was.
 * [awaiting] is set while the routing waits on the user for it, see [awaitsUser].
 */
@Serializable
data class QueuedCapture(
    val id: String,
    val originalFileName: String,
    val queuedAtMillis: Long,
    val attempts: Int = 0,
    val flagged: Boolean = false,
    val zoneId: String? = null,
    val durationMillis: Long? = null,
    val fromWatch: Boolean = false,
    val failure: String? = null,
    val phoneTried: Boolean = false,
    val serverTries: Int = 0,
    val serverTriedAtMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val awaiting: AwaitingUser? = null,
    val fromRing: Boolean = false,
    val openApp: String? = null,
)

/**
 * A capture parked by the routing until the user answers: its transcript and recording time as
 * routed, kept so the answer never costs a second transcription, the [router] the walk ended at
 * (by ref, so the current definition is used on resume, and [routerName] for the row meanwhile),
 * whether that router asks for a [confirm]ation, the needs still [asks]ed about, those
 * [granted] for this one recording, those [denied] for it, which a grant the router gains
 * meanwhile does not override, and the Switch [matches] the walk made, kept with the text they index, with the [fields] they
 * gave the router when parked, shown for the user to see what is sent.
 */
@Serializable
data class AwaitingUser(
    val router: RouterRef,
    val routerName: String,
    val text: String,
    val recordedAt: String,
    val confirm: Boolean,
    val location: RoutedLocation? = null,
    val asks: Set<Need> = emptySet(),
    val granted: Set<Need> = emptySet(),
    val denied: Set<Need> = emptySet(),
    val matches: List<TextMatch> = emptyList(),
    val fields: Map<String, String> = emptyMap(),
) {
    /** The text with the cut words out, when any were: what the router is sent in place of [text]. */
    val sends: String? get() = RoutedText("", recordedAt, text, matches = matches).unmatchedText.takeIf { it != text }

    /** Nothing left to ask: the user confirmed, or there was nothing to confirm once the questions were answered. */
    val settled: Boolean get() = asks.isEmpty() && !confirm
}

/** The location an entry carries, or null when it has none. */
val QueuedCapture.location: RoutedLocation?
    get() = if (latitude != null && longitude != null) RoutedLocation(latitude, longitude, accuracyMeters) else null

const val MAX_ZONE_ID_CHARS = 64

private const val WATCH_RECORDING_PREFIX = "watch_"
private const val RING_RECORDING_PREFIX = "ring_"

/**
 * A new recording's file name; one made on the watch or the ring is named so a copy kept outside the queue is
 * queued as one again.
 */
fun captureFileName(capturedAtMillis: Long, extension: String, fromWatch: Boolean, fromRing: Boolean = false): String {
    val prefix = when {
        fromWatch -> WATCH_RECORDING_PREFIX
        fromRing -> RING_RECORDING_PREFIX
        else -> "recording_"
    }
    return "$prefix$capturedAtMillis.$extension"
}

fun isWatchRecordingName(name: String): Boolean = name.startsWith(WATCH_RECORDING_PREFIX)

fun isRingRecordingName(name: String): Boolean = name.startsWith(RING_RECORDING_PREFIX)

private val CAPTURE_FILE_NAME = Regex("^(?:watch|ring|recording)_(\\d{1,15})\\.[A-Za-z0-9]+$")

/** The start time a [captureFileName] carries, or null for any other name. */
fun capturedAtFromName(name: String): Long? = CAPTURE_FILE_NAME.matchEntire(name)?.groupValues?.get(1)?.toLong()

/** The zone [zoneId] names, or null when it is absent, too long, or not a zone this runtime knows. */
fun captureZone(zoneId: String?): TimeZone? {
    if (zoneId == null || zoneId.length > MAX_ZONE_ID_CHARS) return null
    return try {
        TimeZone.of(zoneId)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Attempts after which an entry is flagged for the user instead of retried. */
const val MAX_QUEUE_ATTEMPTS = 10

/** An entry the drain no longer picks up on its own. */
val QueuedCapture.needsReview: Boolean get() = flagged || attempts >= MAX_QUEUE_ATTEMPTS

/** An entry waiting on the user, which the drain serves only when asked to; a flagged one is a failed capture first. */
val QueuedCapture.awaitsUser: Boolean get() = awaiting != null && !needsReview

/** Parks the entry [id] with [awaiting] and takes its attempt count back to [attempts], in one copy; a no-op when it is absent. */
fun awaitUser(queue: List<QueuedCapture>, id: String, awaiting: AwaitingUser, attempts: Int): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(awaiting = awaiting, attempts = attempts) else it }

/** Records the answer to one question of the parked entry [id]: the need leaves [AwaitingUser.asks], and joins [AwaitingUser.granted] when [allow]ed, [AwaitingUser.denied] otherwise. */
fun answerUser(queue: List<QueuedCapture>, id: String, need: Need, allow: Boolean): List<QueuedCapture> = queue.map {
    val awaiting = it.awaiting
    if (it.id != id || awaiting == null) {
        it
    } else {
        it.copy(
            awaiting = awaiting.copy(
                asks = awaiting.asks - need,
                granted = if (allow) awaiting.granted + need else awaiting.granted - need,
                denied = if (allow) awaiting.denied - need else awaiting.denied + need,
            ),
        )
    }
}

/** Puts the parked entry [id] back behind a confirmation, for a router that came to ask for one while the entry waited. */
fun requireConfirmation(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.map { if (it.id == id && it.awaiting != null) it.copy(awaiting = it.awaiting.copy(confirm = true)) else it }

/** Takes the parked entry [id] off its confirmation, so the next resume request delivers it. */
fun confirmUser(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.map { if (it.id == id && it.awaiting != null) it.copy(awaiting = it.awaiting.copy(confirm = false)) else it }

/** Appends a fresh entry unless [id] is already queued, so a retried write never doubles an entry. */
fun enqueueCapture(queue: List<QueuedCapture>, id: String, originalFileName: String, queuedAtMillis: Long): List<QueuedCapture> =
    if (queue.any { it.id == id }) queue else queue + QueuedCapture(id, originalFileName, queuedAtMillis)

fun removeQueuedCapture(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.filterNot { it.id == id }

fun markPhoneTried(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(phoneTried = true) else it }

/** Failed tries to reach the server after which a long recording goes to the phone, and the least time between two. */
const val MAX_SERVER_TRIES = 3
const val SERVER_TRY_SPACING_MILLIS = 15 * 60 * 1000L

fun markServerTry(queue: List<QueuedCapture>, id: String, atMillis: Long): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(serverTries = it.serverTries + 1, serverTriedAtMillis = atMillis) else it }

/** Marks the foreground entry [id] as having failed once, in place; a no-op when it is absent. */
fun markQueuedCaptureFailed(queue: List<QueuedCapture>, id: String): List<QueuedCapture> =
    queue.map { if (it.id == id) it.copy(attempts = 1) else it }

/**
 * The queue after a drain: [succeeded] entries are removed, [bumped] entries take their new attempt
 * count, are flagged from [MAX_QUEUE_ATTEMPTS] and lose any failure code, and entries added during the drain are kept.
 */
fun applyQueueDrainResults(current: List<QueuedCapture>, succeeded: Set<String>, bumped: Map<String, Int>): List<QueuedCapture> =
    current.mapNotNull { entry ->
        when (entry.id) {
            in succeeded -> null
            in bumped -> {
                val attempts = bumped.getValue(entry.id)
                entry.copy(attempts = attempts, flagged = attempts >= MAX_QUEUE_ATTEMPTS, failure = null)
            }
            else -> entry
        }
    }
