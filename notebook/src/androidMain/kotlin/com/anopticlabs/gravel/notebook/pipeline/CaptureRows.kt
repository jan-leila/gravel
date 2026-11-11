package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureQueueItem
import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.addCaptureItem
import com.anopticlabs.gravel.notebook.capture.captureBatchAfterSettle
import com.anopticlabs.gravel.notebook.capture.syncAwaitingCaptureItems
import com.anopticlabs.gravel.notebook.capture.withCaptureItemStage
import com.anopticlabs.gravel.notebook.capture.withRequeuedCaptureItem
import com.anopticlabs.gravel.notebook.capture.withSettledCaptureItem
import com.anopticlabs.gravel.notebook.capture.withoutCaptureItem
import com.anopticlabs.gravel.notebook.routers.Need
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The capture rows for this process, in memory only: the queue is the durable record. */
class CaptureRows {
    private val lock = Any()
    private val _items = MutableStateFlow<List<CaptureQueueItem>>(emptyList())
    private val _batchSubmitted = MutableStateFlow(0)

    val items: StateFlow<List<CaptureQueueItem>> = _items.asStateFlow()

    /** Captures in the current batch, for the "n of n" line; back to zero once nothing is in flight. */
    val batchSubmitted: StateFlow<Int> = _batchSubmitted.asStateFlow()

    /** Adds a QUEUED row for [id], or turns its settled row back into one; a row still in flight is left alone. */
    fun queued(id: String, createdAt: Long) = synchronized(lock) {
        val fresh = CaptureQueueItem(id, createdAt, CaptureQueueItem.Status.QUEUED)
        val existing = _items.value.firstOrNull { it.id == id }
        if (existing?.inFlight == true) return@synchronized
        _items.value = if (existing == null) addCaptureItem(_items.value, fresh) else _items.value.map { if (it.id == id) fresh else it }
        _batchSubmitted.value += 1
    }

    fun stage(id: String, stage: CaptureStage) = synchronized(lock) {
        _items.value = withCaptureItemStage(_items.value, id, stage)
    }

    fun settle(
        id: String,
        status: CaptureQueueItem.Status,
        kind: CaptureQueueItem.Kind?,
        label: String?,
        noteId: String? = null,
        reminderId: Int? = null,
        transcript: String? = null,
        target: String? = null,
        asks: Set<Need> = emptySet(),
        confirm: Boolean = false,
    ) = synchronized(lock) {
        _items.value = withSettledCaptureItem(_items.value, id, status, kind, label, noteId, reminderId, transcript, target, asks, confirm)
        _batchSubmitted.value = captureBatchAfterSettle(_items.value, _batchSubmitted.value)
    }

    /** The AWAITING_USER rows as the queue has them, see [syncAwaitingCaptureItems]; [awaiting] are the entries waiting on the user. */
    fun syncAwaiting(awaiting: List<QueuedCapture>) = synchronized(lock) {
        _items.value = syncAwaitingCaptureItems(_items.value, awaiting)
    }

    fun requeue(id: String) = synchronized(lock) {
        _items.value = withRequeuedCaptureItem(_items.value, id)
    }

    fun remove(id: String) = synchronized(lock) {
        _items.value = withoutCaptureItem(_items.value, id)
        _batchSubmitted.value = captureBatchAfterSettle(_items.value, _batchSubmitted.value)
    }

    /** Removes row [id] only if it is settled; a row the drain has taken up again stays. */
    fun removeSettled(id: String) = synchronized(lock) {
        if (_items.value.any { it.id == id && !it.inFlight }) remove(id)
    }
}
