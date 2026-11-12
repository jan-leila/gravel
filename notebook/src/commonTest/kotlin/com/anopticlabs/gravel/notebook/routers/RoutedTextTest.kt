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

    private fun cut(text: String, vararg spans: Pair<Int, Int>) = RoutedText.unmatched(text, spans.map { (start, end) -> TextMatch("s", "S", start, end) })

    @Test
    fun theUnmatchedTextIsTheTextWithTheMatchesCutOutAndTheSeparatorsAtTheCutsGone() {
        assertEquals("buy milk", cut("add a task: buy milk", 0 to 10))
        assertEquals("Buy milk and eggs.", cut("Buy milk, todo, and eggs.", 8 to 14))
        assertEquals("buy milk", cut("buy milk, todo.", 8 to 14))
        assertEquals("call Sam", cut("work task call Sam", 0 to 4, 5 to 9), "adjacent matches")
        assertEquals("call Sam", cut("work task call Sam", 0 to 9, 5 to 9), "overlapping matches")
        assertEquals("call Sam", cut("work task call Sam", 5 to 9, 0 to 4), "in any order")
        assertEquals("", cut("todo", 0 to 4), "a match of the whole text leaves nothing")
    }

    @Test
    fun withNoMatchOrOnlyEmptyOnesTheUnmatchedTextIsTheTextAsIs() {
        assertEquals("  as heard, ", cut("  as heard, "))
        assertEquals("  as heard, ", cut("  as heard, ", 0 to 0))
        assertEquals("as heard", RoutedText("c1", "2026-09-22T17:00:00+02:00", "as heard").unmatchedText)
    }

    /** Matches come back from storage: a span past the text is clamped, never thrown on. */
    @Test
    fun aSpanPastTheTextIsClamped() {
        assertEquals("buy", cut("buy milk", 3 to 99))
        assertEquals("milk", cut("buy milk", -5 to 3))
        val text = RoutedText("c1", "2026-09-22T17:00:00+02:00", "buy milk")
        assertEquals("milk", text.matchedText(TextMatch("s", "S", 4, 99)))
        assertEquals("", text.matchedText(TextMatch("s", "S", 6, 2)))
    }

    @Test
    fun theMatchesAreSerializedOnlyWhenThereAreAny() {
        val routed = RoutedText("c1", "2026-09-22T17:00:00+02:00", "todo milk", matches = listOf(TextMatch("split", "Split", 0, 4)))
        val json = NotebookJson.encodeToString(routed)
        assertEquals(setOf("captureId", "recordedAt", "text", "matches"), NotebookJson.parseToJsonElement(json).jsonObject.keys)
        assertEquals(routed, NotebookJson.decodeFromString<RoutedText>(json))
    }
}
