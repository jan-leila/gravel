package com.anopticlabs.gravel.watchsync

import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** A first sync waiting on the user, answered through [FirstSyncPrompt.answer]. */
class PendingFirstSync(val request: FirstSyncRequest) {
    internal val answer = CompletableDeferred<FirstSyncChoice>()
}

/** Tells the user, wherever the app is, that a watch waits on a [FirstSyncPrompt] answer. */
interface FirstSyncNotifier {
    fun show(request: FirstSyncRequest)
    fun dismiss(identifier: PebbleIdentifier)
}

/**
 * The [FirstSyncDecider] that asks the user: each request waits in [pending] until [answer] is
 * called or the watch disconnects, and the watch's sync waits with it. A watch the user keeps is
 * recorded in [KeptWatches].
 */
class FirstSyncPrompt(
    private val notifier: FirstSyncNotifier,
    private val keptWatches: KeptWatches,
) : FirstSyncDecider {
    private val _pending = MutableStateFlow<List<PendingFirstSync>>(emptyList())
    val pending: StateFlow<List<PendingFirstSync>> = _pending.asStateFlow()

    override suspend fun decide(request: FirstSyncRequest): FirstSyncChoice {
        val entry = PendingFirstSync(request)
        _pending.update { list -> list.filterNot { it.request.identifier == request.identifier } + entry }
        notifier.show(request)
        try {
            val choice = entry.answer.await()
            if (choice == FirstSyncChoice.KeepWatch) keptWatches.add(request.serial)
            return choice
        } finally {
            _pending.update { list -> list - entry }
            notifier.dismiss(request.identifier)
        }
    }

    fun answer(identifier: PebbleIdentifier, choice: FirstSyncChoice) {
        _pending.value.firstOrNull { it.request.identifier == identifier }?.answer?.complete(choice)
    }
}

/** Serials of the watches whose contents the user kept on a first sync, kept across launches. */
class KeptWatches(private val settings: Settings) {
    private val _serials = MutableStateFlow(read())
    val serials: StateFlow<Set<String>> = _serials.asStateFlow()

    fun add(serial: String) {
        val next = _serials.updateAndGet { it + serial }
        settings.putString(KEY, next.joinToString(SEPARATOR))
    }

    private fun read(): Set<String> =
        settings.getStringOrNull(KEY)?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private companion object {
        const val KEY = "gravel_kept_watch_serials"
        const val SEPARATOR = ","
    }
}
