package com.anopticlabs.gravel.watchsync

import com.russhwolf.settings.MapSettings
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirstSyncPromptTest {
    private class RecordingNotifier : FirstSyncNotifier {
        val shown = mutableListOf<PebbleIdentifier>()
        val dismissed = mutableListOf<PebbleIdentifier>()
        override fun show(request: FirstSyncRequest) { shown += request.identifier }
        override fun dismiss(identifier: PebbleIdentifier) { dismissed += identifier }
    }

    private val watch = PebbleSocketIdentifier("10.0.0.2:1234")
    private val request = FirstSyncRequest(watch, serial = "Q123", usedWithAnotherPhone = false)

    @Test
    fun keepingRecordsTheWatchAndClearsThePrompt() = runTest {
        val settings = MapSettings()
        val notifier = RecordingNotifier()
        val prompt = FirstSyncPrompt(notifier, KeptWatches(settings))
        val choice = async { prompt.decide(request) }
        prompt.pending.first { it.isNotEmpty() }
        prompt.answer(watch, FirstSyncChoice.KeepWatch)
        assertEquals(FirstSyncChoice.KeepWatch, choice.await())
        assertTrue(prompt.pending.value.isEmpty())
        assertEquals(listOf<PebbleIdentifier>(watch), notifier.shown)
        assertEquals(listOf<PebbleIdentifier>(watch), notifier.dismissed)
        assertEquals(setOf("Q123"), KeptWatches(settings).serials.value)
    }

    @Test
    fun replacingRecordsNothing() = runTest {
        val settings = MapSettings()
        val prompt = FirstSyncPrompt(RecordingNotifier(), KeptWatches(settings))
        val choice = async { prompt.decide(request) }
        prompt.pending.first { it.isNotEmpty() }
        prompt.answer(watch, FirstSyncChoice.ReplaceWatch)
        assertEquals(FirstSyncChoice.ReplaceWatch, choice.await())
        assertTrue(KeptWatches(settings).serials.value.isEmpty())
    }

    @Test
    fun aDisconnectWithdrawsThePrompt() = runTest {
        val notifier = RecordingNotifier()
        val prompt = FirstSyncPrompt(notifier, KeptWatches(MapSettings()))
        val choice = async { prompt.decide(request) }
        prompt.pending.first { it.isNotEmpty() }
        choice.cancel()
        yield()
        assertTrue(prompt.pending.value.isEmpty())
        assertEquals(listOf<PebbleIdentifier>(watch), notifier.dismissed)
    }

    @Test
    fun anAnswerForAnotherWatchIsIgnored() = runTest {
        val prompt = FirstSyncPrompt(RecordingNotifier(), KeptWatches(MapSettings()))
        val choice = async { prompt.decide(request) }
        prompt.pending.first { it.isNotEmpty() }
        prompt.answer(PebbleSocketIdentifier("10.0.0.3:1234"), FirstSyncChoice.KeepWatch)
        assertEquals(1, prompt.pending.value.size)
        choice.cancel()
    }
}
