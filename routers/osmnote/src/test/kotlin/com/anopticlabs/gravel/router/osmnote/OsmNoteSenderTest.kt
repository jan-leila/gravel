package com.anopticlabs.gravel.router.osmnote

import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.Location
import com.anopticlabs.gravel.router.common.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsmNoteSenderTest {

    @Test
    fun theRequestCarriesThePositionAndTheTextAsQueryParametersWithTheTokenWhenThereIsOne() {
        val signed = osmNoteRequest("https://api.openstreetmap.org/", "tok", Location(52.52, 13.405, 30f), "bench broken & bin full")
        assertEquals("POST", signed.method)
        assertEquals("https://api.openstreetmap.org/api/0.6/notes?lat=52.5200000&lon=13.4050000&text=bench+broken+%26+bin+full", signed.url)
        assertEquals(mapOf("Authorization" to "Bearer tok"), signed.headers)
        val anonymous = osmNoteRequest("https://master.apis.dev.openstreetmap.org", null, Location(-33.8688197, 151.2092955, null), "x")
        assertTrue(anonymous.headers.isEmpty())
        assertEquals("https://master.apis.dev.openstreetmap.org/api/0.6/notes?lat=-33.8688197&lon=151.2092955&text=x", anonymous.url)
    }

    @Test
    fun aBadNoteOrTokenIsARefusalAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, OsmNoteSender.answer(HttpAnswer(200, "<osm/>")))
        assertEquals(SendResult.Refused("OpenStreetMap refused the note (HTTP 400)"), OsmNoteSender.answer(HttpAnswer(400, "")))
        assertEquals(SendResult.Refused("OpenStreetMap refused the token (HTTP 401)"), OsmNoteSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Retry("OpenStreetMap answered HTTP 429"), OsmNoteSender.answer(HttpAnswer(429, "")))
    }
}
