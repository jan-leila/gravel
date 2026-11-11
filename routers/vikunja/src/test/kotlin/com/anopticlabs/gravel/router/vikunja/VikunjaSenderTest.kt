package com.anopticlabs.gravel.router.vikunja

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals

class VikunjaSenderTest {

    @Test
    fun theRequestPutsATaskOnTheProjectWithTheRecordingTimeWhenGranted() {
        val timed = vikunjaRequest("https://tasks.example.net/", "tok", " 7 ", Delivery("c1", "buy milk", "2026-09-22T17:00:00+02:00", null))
        assertEquals("PUT", timed.method)
        assertEquals("https://tasks.example.net/api/v1/projects/7/tasks", timed.url)
        assertEquals(mapOf("Authorization" to "Bearer tok"), timed.headers)
        assertEquals("""{"title":"buy milk","description":"Recorded 2026-09-22T17:00:00+02:00"}""", timed.body!!.decodeToString())
        val bare = vikunjaRequest("https://tasks.example.net", "tok", "7", Delivery("c1", "buy milk", null, null))
        assertEquals("""{"title":"buy milk"}""", bare.body!!.decodeToString())
    }

    @Test
    fun theTokenTheProjectAndABadTaskAreRefusalsAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, VikunjaSender.answer(HttpAnswer(201, "{}")))
        assertEquals(SendResult.Refused("Vikunja refused the token (HTTP 401)"), VikunjaSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Refused("Vikunja has no such project (HTTP 404)"), VikunjaSender.answer(HttpAnswer(404, "")))
        assertEquals(SendResult.Refused("Vikunja refused the task (HTTP 400)"), VikunjaSender.answer(HttpAnswer(400, "")))
        assertEquals(SendResult.Retry("Vikunja answered HTTP 500"), VikunjaSender.answer(HttpAnswer(500, "")))
        assertEquals(SendResult.Refused("Vikunja redirected the request (HTTP 301); check the address"), VikunjaSender.answer(HttpAnswer(301, "")), "a redirect is an address no retry changes")
        assertEquals(SendResult.Refused("Vikunja has no such API at that address (HTTP 405)"), VikunjaSender.answer(HttpAnswer(405, "")))
    }
}
