package com.anopticlabs.gravel.router.signal

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalSenderTest {

    @Test
    fun theRequestIsTheRelaysSendWithEveryRecipient() {
        val request = signalRequest("http://signal.lan:8080/", null, "+4917000000", signalRecipients(" +4917111111, group.abc ,, "), Delivery("c1", "on my way", null, null))
        assertEquals("POST", request.method)
        assertEquals("http://signal.lan:8080/v2/send", request.url)
        assertTrue(request.headers.isEmpty())
        assertEquals("""{"message":"on my way","number":"+4917000000","recipients":["+4917111111","group.abc"]}""", request.body!!.decodeToString())
        assertEquals(mapOf("Authorization" to "Bearer t"), signalRequest("http://signal.lan:8080", "t", "+1", listOf("+2"), Delivery("c", "x", null, null)).headers)
    }

    @Test
    fun aBadNumberOrTokenIsARefusalAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, SignalSender.answer(HttpAnswer(201, "{}")))
        assertEquals(SendResult.Refused("The Signal relay refused the message, a number perhaps (HTTP 400)"), SignalSender.answer(HttpAnswer(400, "")))
        assertEquals(SendResult.Refused("The Signal relay refused the token (HTTP 401)"), SignalSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Retry("The Signal relay answered HTTP 500"), SignalSender.answer(HttpAnswer(500, "")))
    }
}
