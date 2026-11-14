package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.captureFileName
import com.anopticlabs.gravel.notebook.capture.wavDurationMillis
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import com.anopticlabs.gravel.notebook.storage.QueueStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/** Where a new recording ended up: in the queue, kept as plaintext for a later attempt, or nowhere. */
enum class IngestResult { QUEUED, KEPT, LOST }

/**
 * Takes a finished recording into the capture queue with the time and zone it was made in, and
 * its length when it is a WAV file, then asks the coordinator to process it; [entryQueued] hears
 * of each entry the queue stored, first. A recording the queue could not store, for any reason,
 * is kept under `pending_captures/` for a later run to queue. Runs to the end even when its caller
 * is canceled, since the recording exists nowhere else. [openApp] reads the watch app open on the
 * watch as the recording is taken in.
 */
class CaptureIngest(
    private val queue: QueueStore,
    private val captureQueued: () -> Unit,
    private val entryQueued: (QueuedCapture) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val openApp: suspend () -> String? = { null },
) {
    /**
     * [extension] names the container of [audio], which the drain's decoder reads by its content;
     * [capturedAtMillis] and [capturedIn] date the capture, [location] places it; [fromWatch] marks a recording made on the watch,
     * [fromRing] one from the ring; [readOpenApp] is off for a recording made too long ago for the app open now to say
     * anything about it.
     */
    suspend fun ingest(
        audio: ByteArray,
        extension: String,
        capturedAtMillis: Long = clock(),
        capturedIn: TimeZone = zone(),
        fromWatch: Boolean = false,
        location: RoutedLocation? = null,
        fromRing: Boolean = false,
        readOpenApp: Boolean = true,
    ): IngestResult = withContext(NonCancellable + Dispatchers.IO) {
        // A failure here must not cost the recording.
        val app = try {
            if (readOpenApp) openApp() else null
        } catch (e: Exception) {
            null
        }
        val entry = QueuedCapture(
            newId(), captureFileName(capturedAtMillis, extension, fromWatch, fromRing), capturedAtMillis, zoneId = capturedIn.id,
            durationMillis = wavDurationMillis(audio), fromWatch = fromWatch, fromRing = fromRing,
            latitude = location?.latitude, longitude = location?.longitude, accuracyMeters = location?.accuracyMeters,
            openApp = app,
        )
        val result = try {
            if (queue.enqueue(entry, audio)) IngestResult.QUEUED else keep(entry, audio, null)
        } catch (e: Throwable) {
            keep(entry, audio, e)
        }
        if (result == IngestResult.QUEUED) entryQueued(entry)
        captureQueued()
        result
    }

    private suspend fun keep(entry: QueuedCapture, audio: ByteArray, cause: Throwable?): IngestResult {
        logger.w { "capture ${entry.id} could not be queued: ${cause?.let { it::class.simpleName } ?: "the store did not keep it"}" }
        val kept = try {
            queue.retainPending(audio, entry.id, entry.originalFileName) != null
        } catch (e: Throwable) {
            logger.w { "capture ${entry.id} could not be kept: ${e::class.simpleName}" }
            false
        }
        return if (kept) IngestResult.KEPT else IngestResult.LOST
    }

    private companion object {
        val logger = Logger.withTag("CaptureIngest")
    }
}
