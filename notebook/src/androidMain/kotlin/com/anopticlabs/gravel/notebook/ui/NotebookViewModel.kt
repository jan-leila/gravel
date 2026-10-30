package com.anopticlabs.gravel.notebook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.FailedCapture
import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.notes.NoteDisplay
import com.anopticlabs.gravel.notebook.pipeline.CaptureDrain
import com.anopticlabs.gravel.notebook.pipeline.CaptureHold
import com.anopticlabs.gravel.notebook.pipeline.CaptureNotice
import com.anopticlabs.gravel.notebook.pipeline.CaptureRows
import com.anopticlabs.gravel.notebook.pipeline.FailedCaptureActions
import com.anopticlabs.gravel.notebook.pipeline.UserAnswers
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.pipeline.ProcessingControls
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.sources.PhoneRecorder
import com.anopticlabs.gravel.notebook.sources.RecorderNotice
import com.anopticlabs.gravel.notebook.sources.RecorderState
import com.anopticlabs.gravel.notebook.sources.TypedNoteResult
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import com.anopticlabs.gravel.notebook.storage.QueueStore.AwaitingRemoval
import com.anopticlabs.gravel.notebook.storage.QueueStore.FailedRemoval
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import coredevices.util.PermissionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class NotebookSection { NOTES, REMINDERS, CALENDAR }

/**
 * The section a notification link opens from [current]: Calendar for the missed notice's link; for the others, whose
 * editor or review opens over either section, Reminders from Notes and otherwise the section already shown.
 */
internal fun sectionFor(link: NotebookLink, current: NotebookSection): NotebookSection = when (link) {
    is NotebookLink.Calendar -> NotebookSection.CALENDAR
    NotebookLink.Reminders, NotebookLink.Completions, NotebookLink.Awaiting, is NotebookLink.Edit, is NotebookLink.Occurrence -> remindersFrom(current)
    NotebookLink.BackupSettings, NotebookLink.KeyCheck -> current
}

/** Reminders from Notes; Reminders or Calendar stays as shown. */
internal fun remindersFrom(current: NotebookSection): NotebookSection =
    if (current == NotebookSection.NOTES) NotebookSection.REMINDERS else current

/** Why a failed-list action did not finish: the store failed, or the recording was being tried again. */
enum class FailedNotice { FAILED, NOT_DELETED }

/** Why a record tap did not start a recording before the microphone was reached. */
enum class MicProblem { DENIED, DENIED_FOR_GOOD, NO_WINDOW }

/**
 * What a waiting capture's buttons could not do: the store failed, the capture was no longer
 * waiting, the answer was taken but the grant to keep could not be written, or the drain had the
 * capture at that moment.
 */
enum class AnswerNotice { FAILED, NOT_WAITING, GRANT_NOT_KEPT, BUSY }

/** A listed note an edit replaces, as the editor opened it: its whole text and the hash the index listed it with. */
data class EditTarget(val id: String, val hash: String, val content: String)

/**
 * A note being written, held in memory only: saved screen state would copy it out of the app.
 * [edit] is the note it replaces; null for a new note.
 */
class NoteDraft(folder: String = UNCLASSIFIED_FOLDER, title: String = "", body: String = "", val edit: EditTarget? = null) {
    private val openedTitle = title
    private val openedBody = body
    var title by mutableStateOf(title)
    var body by mutableStateOf(body)
    var folder by mutableStateOf(folder)

    /** Whether closing it would lose typing: any text in a new note, any change in an edit. */
    val changed: Boolean
        get() = if (edit == null) title.isNotBlank() || body.isNotBlank() else titleChanged || body != openedBody

    val titleChanged: Boolean get() = title != openedTitle

    /** Takes [value] as the body when it is within [NoteDisplay.MAX_EDIT_CHARS]; false, with the body kept, otherwise. */
    fun offerBody(value: String): Boolean {
        if (value.length > NoteDisplay.MAX_EDIT_CHARS) return false
        body = value
        return true
    }
}

/** What the status depends on among the rows: the settled rows and how many are in flight, not their stages. */
internal fun statusKey(items: List<CaptureQueueItem>): Any =
    items.filterNot { it.inFlight }.map { it.id to it.status } to items.count { it.inFlight }

class NotebookViewModel(
    private val rows: CaptureRows,
    private val drain: CaptureDrain,
    private val statusReader: NotebookStatusReader,
    private val navigator: NotebookNavigator,
    private val recorder: PhoneRecorder,
    private val typedNotes: TypedNotes,
    private val claimWrites: StateFlow<Long>,
    private val requestDrain: () -> Unit,
    private val recovery: StorageRecoveryRunner,
    private val controls: ProcessingControls,
    private val failedCaptures: FailedCaptureActions,
    private val answers: UserAnswers,
    private val locationAsked: () -> Boolean = { true },
    private val markLocationAsked: () -> Unit = {},
    private val serverTryAgain: () -> Unit = {},
) : ViewModel() {

    var section by mutableStateOf(NotebookSection.NOTES)
        private set

    val recorderState: StateFlow<RecorderState> = recorder.state

    val recorderNotice: StateFlow<RecorderNotice?> = recorder.notice

    var micProblem by mutableStateOf<MicProblem?>(null)
        private set

    /** The note being typed, or null with the editor closed; a process death discards it. */
    var draft by mutableStateOf<NoteDraft?>(null)
        private set

    private var askingForMic = false

    /**
     * Starts a recording once the microphone permission is granted, asking for it through
     * [requestMic] when [hasMic] says it is missing; [requestMic] is null without a window to ask from.
     * The location permission is asked for the same way, once ever, and the recording starts whatever
     * the answer: a recording without a location is still a recording.
     */
    suspend fun record(
        hasMic: suspend () -> Boolean,
        requestMic: (suspend () -> PermissionResult)?,
        hasLocation: suspend () -> Boolean = { true },
        requestLocation: (suspend () -> PermissionResult)? = null,
    ) {
        if (askingForMic) return
        micProblem = null
        if (!hasMic()) {
            if (requestMic == null) {
                micProblem = MicProblem.NO_WINDOW
                return
            }
            askingForMic = true
            val result = try {
                requestMic()
            } finally {
                askingForMic = false
            }
            when (result) {
                PermissionResult.Granted -> Unit
                PermissionResult.RejectedForever -> {
                    micProblem = MicProblem.DENIED_FOR_GOOD
                    return
                }
                else -> {
                    micProblem = MicProblem.DENIED
                    return
                }
            }
        }
        if (requestLocation != null && !locationAsked() && !hasLocation()) {
            markLocationAsked()
            askingForMic = true
            try {
                requestLocation()
            } finally {
                askingForMic = false
            }
        }
        recorder.record()
    }

    fun stopRecording() = recorder.stop()

    fun openDraft(folder: String = UNCLASSIFIED_FOLDER) {
        if (draft == null) draft = NoteDraft(folder)
    }

    fun closeDraft() {
        draft = null
    }

    /** The folders the note editor offers; empty when they could not be read. */
    suspend fun typedNoteFolders(): List<String> = try {
        typedNotes.folders()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Saves a typed note; null when the store failed, so the editor keeps what was typed. */
    suspend fun saveTypedNote(title: String, body: String, folder: String): TypedNoteResult? = try {
        typedNotes.save(title, body, folder)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "typed note not saved: ${e::class.simpleName}" }
        null
    }

    val items: StateFlow<List<CaptureQueueItem>> = rows.items

    val batchSubmitted: StateFlow<Int> = rows.batchSubmitted

    val held: StateFlow<CaptureHold?> = drain.held

    val notice: StateFlow<CaptureNotice?> = drain.notices

    private val _status = MutableStateFlow<NotebookStatus?>(null)
    val status: StateFlow<NotebookStatus?> = _status.asStateFlow()

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun select(section: NotebookSection) {
        this.section = section
    }

    fun dismiss(id: String) = rows.removeSettled(id)

    val pause: StateFlow<ProcessingPause> = controls.pause

    // Off the main thread: a pause waits for the drain's check before its next capture and writes a file.
    fun pauseAll() = control { pauseAll() }

    fun pauseLong() = control { pauseLong() }

    fun resume() = control { resume() }

    /** Ends the "check your server" hold and runs the queue again. */
    fun tryServerAgain() = serverTryAgain()

    /** Lets the long captures [status] counted as waiting for the charger start now, and reads the status again. */
    fun processNow(status: NotebookStatus) = control {
        processNow(status.waitingForPower)
        refresh()
    }

    // One at a time, in the order of the taps.
    private val controlDispatcher = Dispatchers.IO.limitedParallelism(1)

    private fun control(action: ProcessingControls.() -> Unit) {
        viewModelScope.launch(controlDispatcher) { controls.action() }
    }

    /**
     * Whether the failed list is open. A status read with nothing flagged closes it, unless a notice is
     * showing or the read failed in a way that may pass ([StorageTrouble.Unreadable]).
     */
    var failedOpen by mutableStateOf(false)
        private set

    /** The failed recording a Delete is asking about. */
    var deleteAsk by mutableStateOf<FailedCapture?>(null)
        private set

    /** Why the last failed-list action did nothing. */
    var failedNotice by mutableStateOf<FailedNotice?>(null)
        private set

    fun openFailed() {
        failedOpen = true
        failedNotice = null
    }

    fun closeFailed() {
        failedOpen = false
        deleteAsk = null
        failedNotice = null
    }

    fun retryFailed(id: String) = failedAction { retry(setOf(id)) }

    /** Retries the listed recordings that can be retried, not any flagged after the list was read. */
    fun retryAllFailed() {
        val ids = status.value?.failed.orEmpty().filter { it.retryable }.mapTo(HashSet()) { it.id }
        failedAction { retry(ids) }
    }

    fun askDelete(capture: FailedCapture) {
        deleteAsk = capture
    }

    fun cancelDelete() {
        deleteAsk = null
    }

    fun confirmDelete() {
        val capture = deleteAsk ?: return
        deleteAsk = null
        failedAction {
            if (delete(capture.id) == FailedRemoval.NOT_FLAGGED) failedNotice = FailedNotice.NOT_DELETED else rows.removeSettled(capture.id)
        }
    }

    // The store calls suspend and so free the control dispatcher: the lock keeps the taps in order, and
    // NonCancellable keeps a tapped action from being dropped when the screen closes.
    private val failedActions = Mutex()

    private fun failedAction(action: suspend FailedCaptureActions.() -> Unit) {
        viewModelScope.launch(controlDispatcher) {
            try {
                withContext(NonCancellable) {
                    failedActions.withLock {
                        failedNotice = null
                        failedCaptures.action()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "failed recording action not done: ${e::class.simpleName}" }
                failedNotice = FailedNotice.FAILED
            }
            refresh()
        }
    }

    var answerNotice by mutableStateOf<AnswerNotice?>(null)
        private set

    fun clearAnswerNotice() {
        answerNotice = null
    }

    fun answer(id: String, need: Need, allow: Boolean, remember: Boolean) = awaitingAction {
        when (answer(id, need, allow, remember)) {
            UserAnswers.Answered.TAKEN -> null
            UserAnswers.Answered.GRANT_NOT_KEPT -> AnswerNotice.GRANT_NOT_KEPT
            UserAnswers.Answered.NOT_WAITING -> AnswerNotice.NOT_WAITING
        }
    }

    fun confirm(id: String) = awaitingAction {
        confirm(id)
        null
    }

    fun dismissAwaiting(id: String) = awaitingAction { dismissed(id) }

    /**
     * Drops the waiting capture [id] and starts a new recording in its place, with the permissions
     * asked for as [record] asks; a capture that could not be dropped is kept and nothing is recorded,
     * so the user never ends up with both without knowing.
     */
    suspend fun reRecord(
        id: String,
        hasMic: suspend () -> Boolean,
        requestMic: (suspend () -> PermissionResult)?,
        hasLocation: suspend () -> Boolean = { true },
        requestLocation: (suspend () -> PermissionResult)? = null,
    ) {
        val notice = try {
            withContext(controlDispatcher) { answers.dismissed(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w { "waiting recording not dropped: ${e::class.simpleName}" }
            AnswerNotice.FAILED
        }
        refresh()
        if (notice != null) {
            answerNotice = notice
            return
        }
        record(hasMic, requestMic, hasLocation, requestLocation)
    }

    private suspend fun UserAnswers.dismissed(id: String): AnswerNotice? = when (dismiss(id)) {
        AwaitingRemoval.REMOVED, AwaitingRemoval.GONE -> {
            rows.remove(id)
            null
        }
        AwaitingRemoval.NOT_AWAITING -> AnswerNotice.BUSY
    }

    // The actions outlive a closed screen (an answer given is never dropped) and run one at a time, in tap order.
    private fun awaitingAction(action: suspend UserAnswers.() -> AnswerNotice?) {
        viewModelScope.launch(controlDispatcher) {
            answerNotice = try {
                withContext(NonCancellable) { answers.action() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w { "waiting recording action not done: ${e::class.simpleName}" }
                AnswerNotice.FAILED
            }
            refresh()
        }
    }

    val recoveryState: StateFlow<RecoveryRunState> get() = recovery.state

    /** Try again also runs the rebuilds, which the start's housekeeping runs once. */
    fun retry() = recovery.repair(StorageTrouble.Unreadable)

    fun startFresh(trouble: StorageTrouble.Damaged) = recovery.startFresh(trouble)

    fun startOver(force: Boolean = false) = recovery.startOver(force)

    fun restore(status: NotebookStatus) {
        status.trouble?.let { recovery.restore(it, status.automaticBackupAt) }
    }

    /** Asks the running [watch] for another read. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    /**
     * Runs while the screen is started: asks for a drain, shows the section a notification link
     * belongs to (the Reminders ViewModel takes the link), and reads the status at start, on
     * [refresh], after each drain pass, after each write to the parked claims ([claimWrites]; the
     * review takes claims outside the drain), after each storage card action, after each pause or
     * resume, and whenever [statusKey] changes, one read at a time with the latest winning.
     */
    suspend fun watch() = coroutineScope {
        requestDrain()
        launch {
            navigator.pending.filterNotNull().collect { link ->
                select(sectionFor(link, section))
                // The rows are the destination: nothing opens, so the tab's ViewModel takes the link itself.
                if (link == NotebookLink.Awaiting) {
                    navigator.consume(link)
                    refresh()
                }
            }
        }
        merge(
            rows.items.map(::statusKey).distinctUntilChanged(),
            drain.passes.drop(1),
            refreshes,
            claimWrites.drop(1),
            recovery.state.map { it.runs }.distinctUntilChanged().drop(1),
            controls.pause.drop(1),
        ).collectLatest { readStatus() }
    }

    private suspend fun readStatus() {
        val inFlight = rows.items.value.filter { it.inFlight }.mapTo(HashSet()) { it.id }
        _status.value = statusReader.read(inFlight).also {
            recovery.seen(it.trouble)
            // A troubled read saw no queue, so it has nothing to say about the rows.
            if (it.trouble == null) rows.syncAwaiting(it.awaiting)
            if (it.failed.isEmpty() && failedNotice == null && it.trouble != StorageTrouble.Unreadable) closeFailed()
        }
    }

    private companion object {
        val logger = Logger.withTag("NotebookViewModel")
    }
}
