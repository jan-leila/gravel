package com.anopticlabs.gravel.router.homeassistant

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeAssistantSenderTest {

    @Test
    fun theRequestIsAnAssistSentenceWithTheToken() {
        val request = homeAssistantRequest("http://homeassistant.local:8123/", "tok", "de", Delivery("c1", "Licht aus", null, null))
        assertEquals("POST", request.method)
        assertEquals("http://homeassistant.local:8123/api/conversation/process", request.url)
        assertEquals(mapOf("Authorization" to "Bearer tok"), request.headers)
        assertEquals("""{"text":"Licht aus","language":"de"}""", request.body!!.decodeToString())
        assertEquals("application/json", request.contentType)
    }

    @Test
    fun theTokenAndTheAddressAreRefusalsAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, HomeAssistantSender.answer(HttpAnswer(200, "{}")))
        assertEquals(SendResult.Refused("Home Assistant refused the token (HTTP 401)"), HomeAssistantSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Refused("Home Assistant has no Assist at that address (HTTP 404)"), HomeAssistantSender.answer(HttpAnswer(404, "")))
        assertEquals(SendResult.Retry("Home Assistant answered HTTP 502"), HomeAssistantSender.answer(HttpAnswer(502, "")))
        assertEquals(SendResult.Retry("Home Assistant answered HTTP 429"), HomeAssistantSender.answer(HttpAnswer(429, "")))
    }
}
