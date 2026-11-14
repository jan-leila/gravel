package com.anopticlabs.gravel.router.vikunja

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.ListedRouter
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.SettingValues
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun theTitleIsTheTaskWithoutTheWordsThatRoutedItHere() {
        val routed = Delivery("c1", "add a task: buy milk", null, null, unmatchedText = "buy milk")
        assertEquals("""{"title":"buy milk"}""", vikunjaRequest("https://tasks.example.net", "tok", "7", routed).body!!.decodeToString())
    }

    @Test
    fun mappedParametersFillTheTitleAndTheDescription() {
        val mapped = Delivery("c1", "task buy milk for the party", "2026-09-22T17:00:00+02:00", null, fields = mapOf("title" to "buy milk", "description" to "for the party"))
        assertEquals("""{"title":"buy milk","description":"for the party"}""", vikunjaRequest("https://tasks.example.net", "tok", "7", mapped).body!!.decodeToString())
    }

    /** A project is a router of its own, its id stable for the project; a saved filter and an archived project are not. */
    @Test
    fun theProjectsAreReadPageByPage() {
        val pages = mutableListOf<String>()
        val first = (1..50).joinToString(",", "[", "]") { """{"id":$it,"title":"P$it"}""" }
        val second = """[{"id":51,"title":"Groceries"},{"id":-2,"title":"Filter"},{"id":52,"title":"Old","is_archived":true},{"id":53}]"""
        val routers = vikunjaProjects("https://tasks.example.net/", "tok") { request ->
            pages += request.url
            assertEquals(mapOf("Authorization" to "Bearer tok"), request.headers)
            HttpAnswer(200, if (request.url.contains("page=1&")) first else second)
        }
        assertEquals(listOf("https://tasks.example.net/api/v1/projects?page=1&per_page=50", "https://tasks.example.net/api/v1/projects?page=2&per_page=50"), pages)
        assertEquals(52, routers.size)
        val groceries = routers[50]
        assertEquals("Vikunja: Groceries", groceries.label)
        assertEquals(mapOf("project" to "51"), groceries.values)
        assertEquals("recorded_at", groceries.needs)
        assertEquals(RouterIds.of("vikunja", "51"), groceries.id)
        assertEquals("Vikunja: Project 53", routers[51].label)
        assertEquals("Vikunja answered HTTP 401", assertFailsWith<IOException> { vikunjaProjects("https://tasks.example.net", "bad") { HttpAnswer(401, "") } }.message)
        assertFailsWith<IOException> { vikunjaProjects("http://tasks.example.net", "tok") { HttpAnswer(200, "[]") } }
    }

    @Test
    fun aListedRouterSendsToItsProjectAndAGoneOneIsRefused() {
        val dir = Files.createTempDirectory("vikunja").toFile()
        try {
            val list = RouterList(File(dir, "list.json"))
            list.replace(listOf(ListedRouter("vikunja-1", "Vikunja: Groceries", values = mapOf("project" to "51"))))
            val settings = SettingValues { mapOf("url" to "https://tasks.example.net", "token" to "tok")[it] }
            val sent = mutableListOf<String>()
            val sender = VikunjaSender(settings, list) { sent += it.url; HttpAnswer(201, "{}") }
            assertEquals(SendResult.Sent, sender.send(Delivery("c1", "milk", null, null, "vikunja-1")))
            assertEquals(listOf("https://tasks.example.net/api/v1/projects/51/tasks"), sent)
            assertEquals(SendResult.Refused(RouterReceiver.NOT_LISTED), sender.send(Delivery("c1", "milk", null, null, "vikunja-2")))
            assertEquals(SendResult.Refused(VikunjaApp.NO_DEFAULT), sender.send(Delivery("c1", "milk", null, null)))
        } finally {
            dir.deleteRecursively()
        }
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
