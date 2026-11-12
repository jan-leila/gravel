package com.anopticlabs.gravel.router.signal

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
    fun theRelaysContactsAreReadWithTheBestNameAndReachableRecipient() {
        var url = ""
        val routers = signalContacts("http://signal.lan:8080/", "tok", "+4917000") { request ->
            url = request.url
            assertEquals(mapOf("Authorization" to "Bearer tok"), request.headers)
            HttpAnswer(
                200,
                """[{"number":"+4917111","uuid":"u1","name":"Sam","profile_name":"Samuel"},""" +
                    """{"number":"","uuid":"u2","name":"","profile":{"given_name":"Kim","lastname":"Lee"}},""" +
                    """{"number":"+4917333","name":""},{"name":"Nobody"}]""",
            )
        }
        assertEquals("http://signal.lan:8080/v1/contacts/%2B4917000", url)
        assertEquals(listOf("Signal Sam", "Signal Kim Lee", "Signal +4917333"), routers.map { it.label })
        assertEquals(listOf("+4917111", "u2", "+4917333"), routers.map { it.values["recipient"] })
        assertEquals(RouterIds.of("signal", "u1"), routers.first().id)
        assertFailsWith<IOException> { signalContacts("http://signal.example.net", null, "+49") { HttpAnswer(200, "[]") } }
    }

    @Test
    fun aTickedContactIsSentToAloneAndTheSetRecipientsAreForTheDeclaredRouter() {
        val dir = Files.createTempDirectory("signal").toFile()
        try {
            val list = RouterList(File(dir, "list.json"))
            list.replace(listOf(ListedRouter("signal-1", "Signal Sam", values = mapOf("recipient" to "+4917111"))))
            val settings = SettingValues { mapOf("url" to "http://signal.lan:8080", "number" to "+4917000")[it] }
            val bodies = mutableListOf<String>()
            val sender = SignalSender(settings, list) { bodies += it.body!!.decodeToString(); HttpAnswer(201, "{}") }
            assertEquals(SendResult.Sent, sender.send(Delivery("c1", "hi", null, null, "signal-1")))
            assertEquals(listOf("""{"message":"hi","number":"+4917000","recipients":["+4917111"]}"""), bodies)
            assertEquals(SendResult.Refused(RouterReceiver.NOT_LISTED), sender.send(Delivery("c1", "hi", null, null, "signal-2")))
            assertEquals(SendResult.Refused(SignalApp.NO_RECIPIENTS), sender.send(Delivery("c1", "hi", null, null)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBadNumberOrTokenIsARefusalAndTheRestIsTriedAgain() {
        assertEquals(SendResult.Sent, SignalSender.answer(HttpAnswer(201, "{}")))
        assertEquals(SendResult.Refused("The Signal relay refused the message, a number perhaps (HTTP 400)"), SignalSender.answer(HttpAnswer(400, "")))
        assertEquals(SendResult.Refused("The Signal relay refused the token (HTTP 401)"), SignalSender.answer(HttpAnswer(401, "")))
        assertEquals(SendResult.Retry("The Signal relay answered HTTP 500"), SignalSender.answer(HttpAnswer(500, "")))
        assertEquals(SendResult.Refused("The Signal relay redirected the request (HTTP 301); check the address"), SignalSender.answer(HttpAnswer(301, "")), "a redirect is an address no retry changes")
        assertEquals(SendResult.Refused("The Signal relay has no such API at that address (HTTP 404)"), SignalSender.answer(HttpAnswer(404, "")))
    }
}
