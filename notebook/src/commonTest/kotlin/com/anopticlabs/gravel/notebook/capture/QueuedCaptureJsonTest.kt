package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import com.anopticlabs.gravel.notebook.routers.RouterRef
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuedCaptureJsonTest {

    private val full = QueuedCapture("7c1e", "recording_1.m4a", 1_716_998_400_500L, attempts = 3, flagged = true, zoneId = "Europe/Berlin", phoneTried = true,
        serverTries = 2, serverTriedAtMillis = 1_716_998_500_000L)

    @Test
    fun theOnDiskKeysAreTheFieldNamesAndDefaultsAreOmitted() {
        val keys = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(full)).jsonObject.keys
        assertEquals(setOf("id", "originalFileName", "queuedAtMillis", "attempts", "flagged", "zoneId", "phoneTried", "serverTries", "serverTriedAtMillis"), keys)

        val fresh = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(QueuedCapture("a", "a.m4a", 1L))).jsonObject
        assertEquals(setOf("id", "originalFileName", "queuedAtMillis"), fresh.keys)
    }

    @Test
    fun entriesRoundTripAloneAndAsTheQueueList() {
        assertEquals(full, NotebookJson.decodeFromString<QueuedCapture>(NotebookJson.encodeToString(full)))
        val queue = listOf(full, QueuedCapture("b", "b.m4a", 2L))
        val json = NotebookJson.encodeToString(queue)
        assertEquals(2, NotebookJson.parseToJsonElement(json).jsonArray.size)
        assertEquals(queue, NotebookJson.decodeFromString<List<QueuedCapture>>(json))
    }

    @Test
    fun aMissingCounterReadsAsItsDefaultAndAnUnknownKeyIsIgnored() {
        val e = NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a","queuedAtMillis":5,"future":1}""")
        assertEquals(QueuedCapture("x", "x.m4a", 5L, 0, false), e)
    }

    @Test
    fun anEntryCarriesWhereItWasMadeOnlyWhenBothCoordinatesAreThere() {
        val placed = QueuedCapture("p", "p.wav", 1L, latitude = 52.52, longitude = 13.405, accuracyMeters = 12.5f)
        assertEquals(setOf("id", "originalFileName", "queuedAtMillis", "latitude", "longitude", "accuracyMeters"), NotebookJson.parseToJsonElement(NotebookJson.encodeToString(placed)).jsonObject.keys)
        assertEquals(placed, NotebookJson.decodeFromString<QueuedCapture>(NotebookJson.encodeToString(placed)))
        assertEquals(RoutedLocation(52.52, 13.405, 12.5f), placed.location)
        assertEquals(RoutedLocation(52.52, 13.405), placed.copy(accuracyMeters = null).location)
        assertEquals(null, placed.copy(longitude = null).location)
        assertEquals(null, QueuedCapture("x", "x.m4a", 5L).location)
    }

    /** The wait on the user is one nested object, absent until a capture is parked; a flagged entry is a failed capture before anything else. */
    @Test
    fun aParkedEntryCarriesWhatTheUserHasToSeeAndAFlaggedOneIsNotWaiting() {
        val awaiting = AwaitingUser(
            RouterRef("a".repeat(64), "tasks"), "Tasks", "buy milk", "2026-09-22T17:00:00+02:00", confirm = true,
            location = RoutedLocation(52.52, 13.405), asks = setOf(Need.LOCATION), granted = setOf(Need.RECORDED_AT),
        )
        val parked = QueuedCapture("p", "p.wav", 1L, awaiting = awaiting)
        val json = NotebookJson.encodeToString(parked)
        val fields = NotebookJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("id", "originalFileName", "queuedAtMillis", "awaiting"), fields.keys)
        assertEquals(setOf("router", "routerName", "text", "recordedAt", "confirm", "location", "asks", "granted"), fields.getValue("awaiting").jsonObject.keys)
        assertEquals(parked, NotebookJson.decodeFromString<QueuedCapture>(json))
        assertTrue(parked.awaitsUser)
        assertFalse(parked.copy(flagged = true).awaitsUser)
        assertFalse(parked.copy(attempts = MAX_QUEUE_ATTEMPTS).awaitsUser)
        assertFalse(QueuedCapture("x", "x.m4a", 5L).awaitsUser)
        assertEquals(null, NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a","queuedAtMillis":5}""").awaiting)
        val bare = NotebookJson.decodeFromString<AwaitingUser>("""{"router":{"id":"notebook"},"routerName":"Notebook","text":"t","recordedAt":"2026-09-22T17:00:00+02:00","confirm":false}""")
        assertEquals(AwaitingUser(RouterRef.gravel("notebook"), "Notebook", "t", "2026-09-22T17:00:00+02:00", confirm = false), bare)
        assertTrue(bare.settled)
        assertFalse(bare.copy(confirm = true).settled)
        assertFalse(bare.copy(asks = setOf(Need.LOCATION)).settled)
    }

    @Test
    fun anEntryWrittenBeforeTheZoneWasStoredHasNone() {
        assertEquals(null, NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a","queuedAtMillis":5}""").zoneId)
    }

    @Test
    fun aZoneIsUsedOnlyWhenTheRuntimeKnowsIt() {
        assertEquals("Europe/Berlin", captureZone("Europe/Berlin")?.id)
        assertEquals(null, captureZone(null))
        assertEquals(null, captureZone("Not/AZone"))
        assertEquals(null, captureZone(""))
        assertEquals(null, captureZone("Europe/" + "x".repeat(MAX_ZONE_ID_CHARS)))
    }

    @Test
    fun anEntryWithoutItsIdNameOrTimeIsAFormatFailure() {
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<QueuedCapture>("""{"originalFileName":"x.m4a","queuedAtMillis":5}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","queuedAtMillis":5}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<QueuedCapture>("""{"id":"x","originalFileName":"x.m4a"}""") }
        assertFailsWith<SerializationException> { NotebookJson.decodeFromString<List<QueuedCapture>>("""[null]""") }
    }
}
