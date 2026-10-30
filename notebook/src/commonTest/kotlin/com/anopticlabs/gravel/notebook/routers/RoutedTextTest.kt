package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

class RoutedTextTest {

    private val here = RoutedLocation(52.52, 13.405, 12.5f)

    @Test
    fun carriesTheRecordingsStartInItsZoneTheWholeTextAndWhereItWasMade() {
        val text = RoutedText.of("c1", Instant.parse("2026-09-22T15:00:00Z"), TimeZone.of("Europe/Berlin"), "  the whole transcript, as heard  ", here)
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "  the whole transcript, as heard  ", here), text)
        assertEquals(null, RoutedText.of("c1", Instant.parse("2026-09-22T15:00:00Z"), TimeZone.UTC, "x").location)
    }

    /** The payload is the router API: these keys, and nothing else, can reach an app or server; the location only when there is one. */
    @Test
    fun theSerializedPayloadHasExactlyTheseFields() {
        val json = NotebookJson.encodeToString(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello"))
        assertEquals(setOf("captureId", "recordedAt", "text"), NotebookJson.parseToJsonElement(json).jsonObject.keys)
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello"), NotebookJson.decodeFromString<RoutedText>(json))

        val placed = NotebookJson.encodeToString(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello", here))
        val fields = NotebookJson.parseToJsonElement(placed).jsonObject
        assertEquals(setOf("captureId", "recordedAt", "text", "location"), fields.keys)
        assertEquals(setOf("latitude", "longitude", "accuracyMeters"), fields.getValue("location").jsonObject.keys)
        assertEquals(RoutedText("c1", "2026-09-22T17:00:00+02:00", "hello", here), NotebookJson.decodeFromString<RoutedText>(placed))
        val rough = NotebookJson.encodeToString(RoutedLocation(1.0, 2.0))
        assertEquals(setOf("latitude", "longitude"), NotebookJson.parseToJsonElement(rough).jsonObject.keys)
    }
}
