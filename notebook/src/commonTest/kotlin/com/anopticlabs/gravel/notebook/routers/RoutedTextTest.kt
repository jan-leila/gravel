package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

class RoutedTextTest {

    @Test
    fun carriesTheRecordingsStartInItsZoneAndTheWholeText() {
        val text = RoutedText.of("c1", Instant.parse("2026-09-22T15:00:00Z"), TimeZone.of("Europe/Berlin"), "  the whole transcript, as heard  ")
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "  the whole transcript, as heard  "), text)
    }

    /** The payload is the router API: these three keys, and nothing else, reach every app and server. */
    @Test
    fun theSerializedPayloadHasExactlyTheThreeFields() {
        val json = NotebookJson.encodeToString(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello"))
        assertEquals(setOf("captureId", "recordedAt", "text"), NotebookJson.parseToJsonElement(json).jsonObject.keys)
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello"), NotebookJson.decodeFromString<RoutedText>(json))
    }
}
