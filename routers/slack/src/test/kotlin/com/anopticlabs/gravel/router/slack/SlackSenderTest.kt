package com.anopticlabs.gravel.router.slack

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun aTwoHundredIsSentAFourHundredARefusalAndTheRestATry() {
        assertEquals(SendResult.Sent, SlackSender.answer(HttpAnswer(200, "ok")))
        assertEquals(SendResult.Refused("Slack refused the webhook (HTTP 404)"), SlackSender.answer(HttpAnswer(404, "no_service")))
        assertEquals(SendResult.Retry("Slack answered HTTP 429"), SlackSender.answer(HttpAnswer(429, "")))
        assertEquals(SendResult.Retry("Slack answered HTTP 503"), SlackSender.answer(HttpAnswer(503, "")))
    }
}
