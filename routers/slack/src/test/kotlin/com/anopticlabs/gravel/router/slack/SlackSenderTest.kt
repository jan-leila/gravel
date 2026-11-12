package com.anopticlabs.gravel.router.slack

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

class SlackSenderTest {

    @Test
    fun theRequestPostsTheTextAsSlackWantsIt() {
        val request = slackRequest("https://hooks.slack.com/services/T/B/x", Delivery("c1", "buy \"milk\"\nand eggs", "2026-09-22T17:00:00+02:00", null))
        assertEquals("POST", request.method)
        assertEquals("https://hooks.slack.com/services/T/B/x", request.url)
        assertEquals("application/json", request.contentType)
        assertEquals("""{"text":"buy \"milk\"\nand eggs"}""", request.body!!.decodeToString())
    }

    @Test
    fun theBotsChannelsAreReadFollowingTheCursor() {
        val urls = mutableListOf<String>()
        val routers = slackChannels("xoxb-1") { request ->
            urls += request.url
            assertEquals(mapOf("Authorization" to "Bearer xoxb-1"), request.headers)
            if (request.url.contains("cursor=")) {
                HttpAnswer(200, """{"ok":true,"channels":[{"id":"C2","name":"random"}],"response_metadata":{"next_cursor":""}}""")
            } else {
                HttpAnswer(200, """{"ok":true,"channels":[{"id":"C1","name":"general"},{"name":"no id"}],"response_metadata":{"next_cursor":"dXNlcjpV=="}}""")
            }
        }
        assertEquals(
            listOf(
                "https://slack.com/api/users.conversations?types=public_channel,private_channel&exclude_archived=true&limit=200",
                "https://slack.com/api/users.conversations?types=public_channel,private_channel&exclude_archived=true&limit=200&cursor=dXNlcjpV%3D%3D",
            ),
            urls,
        )
        assertEquals(listOf("Slack #general", "Slack #random"), routers.map { it.label })
        assertEquals(mapOf("channel" to "C1"), routers.first().values)
        assertEquals(RouterIds.of("slack", "C1"), routers.first().id)
        assertEquals("Slack refused the list: missing_scope", assertFailsWith<IOException> { slackChannels("xoxb-1") { HttpAnswer(200, """{"ok":false,"error":"missing_scope"}""") } }.message)
    }

    @Test
    fun aListedChannelIsPostedToByTheBot() {
        val dir = Files.createTempDirectory("slack").toFile()
        try {
            val list = RouterList(File(dir, "list.json"))
            list.replace(listOf(ListedRouter("slack-1", "Slack #general", values = mapOf("channel" to "C1"))))
            val settings = SettingValues { mapOf("bot_token" to "xoxb-1")[it] }
            val bodies = mutableListOf<String>()
            val sender = SlackSender(settings, list) { request ->
                assertEquals("https://slack.com/api/chat.postMessage", request.url)
                assertEquals(mapOf("Authorization" to "Bearer xoxb-1"), request.headers)
                bodies += request.body!!.decodeToString()
                HttpAnswer(200, """{"ok":true}""")
            }
            assertEquals(SendResult.Sent, sender.send(Delivery("c1", "hi", null, null, "slack-1")))
            assertEquals(listOf("""{"channel":"C1","text":"hi"}"""), bodies)
            assertEquals(SendResult.Refused(RouterReceiver.NOT_LISTED), sender.send(Delivery("c1", "hi", null, null, "slack-2")))
            assertEquals(SendResult.Refused("The Slack router is not set up"), sender.send(Delivery("c1", "hi", null, null)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPostIsReadFromSlacksOkAndErrorCode() {
        assertEquals(SendResult.Sent, SlackSender.postAnswer(HttpAnswer(200, """{"ok":true,"ts":"1"}""")))
        assertEquals(SendResult.Refused("Slack refused the post: not_in_channel"), SlackSender.postAnswer(HttpAnswer(200, """{"ok":false,"error":"not_in_channel"}""")))
        assertEquals(SendResult.Retry("Slack answered ratelimited"), SlackSender.postAnswer(HttpAnswer(200, """{"ok":false,"error":"ratelimited"}""")))
        assertEquals(SendResult.Retry("Slack answered HTTP 429"), SlackSender.postAnswer(HttpAnswer(429, "")))
        assertEquals(SendResult.Refused("Slack refused the post (HTTP 403)"), SlackSender.postAnswer(HttpAnswer(403, "")))
        assertEquals(SendResult.Retry("Slack's answer was not readable"), SlackSender.postAnswer(HttpAnswer(200, "<html>")))
    }

    @Test
    fun aTwoHundredIsSentAFourHundredARefusalAndTheRestATry() {
        assertEquals(SendResult.Sent, SlackSender.answer(HttpAnswer(200, "ok")))
        assertEquals(SendResult.Refused("Slack refused the webhook (HTTP 404)"), SlackSender.answer(HttpAnswer(404, "no_service")))
        assertEquals(SendResult.Retry("Slack answered HTTP 429"), SlackSender.answer(HttpAnswer(429, "")))
        assertEquals(SendResult.Retry("Slack answered HTTP 503"), SlackSender.answer(HttpAnswer(503, "")))
        assertEquals(SendResult.Refused("Slack redirected the request (HTTP 302); check the address"), SlackSender.answer(HttpAnswer(302, "")), "a redirect is an address no retry changes")
    }
}
