package com.anopticlabs.gravel.router.email

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.SettingValues
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

class EmailSenderTest {

    private val values = mapOf(
        EmailApp.KEY_HOST to "mail.example.net", EmailApp.KEY_PORT to "587", EmailApp.KEY_USERNAME to "u", EmailApp.KEY_PASSWORD to "p",
        EmailApp.KEY_FROM to "a@example.net", EmailApp.KEY_TO to "b@example.net",
    )

    private val settings = object : SettingValues {
        override fun get(key: String): String? = values[key]
    }

    private class Failing(private val failure: IOException) : SmtpClient("mail.example.net", 587, SmtpSecurity.STARTTLS, null) {
        override fun send(username: String, password: String, from: String, to: String, message: ByteArray) = throw failure
    }

    private fun sender(failure: IOException) = EmailSender(settings, { ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, ZoneId.of("UTC")) }, { Failing(failure) })

    private class Recording : SmtpClient("mail.example.net", 587, SmtpSecurity.STARTTLS, null) {
        val to = mutableListOf<String>()
        override fun send(username: String, password: String, from: String, to: String, message: ByteArray) {
            this.to += to
        }
    }

    @Test
    fun aContactIsSentToItsAddressAndTheDeclaredRouterToTheDefault() {
        val dir = Files.createTempDirectory("email").toFile()
        try {
            val list = RouterList(File(dir, "list.json"))
            val sam = EmailApp.contactRouter(mapOf("name" to " Sam ", "address" to "sam@example.net"))
            list.add(sam)
            val client = Recording()
            val sender = EmailSender(settings, client = { client }, list = list)
            assertEquals(SendResult.Sent, sender.send(Delivery("c1", "hi", null, null, sam.id)))
            assertEquals(SendResult.Sent, sender.send(Delivery("c2", "hi", null, null)))
            assertEquals(listOf("sam@example.net", "b@example.net"), client.to)
            assertEquals(SendResult.Refused(RouterReceiver.NOT_LISTED), sender.send(Delivery("c3", "hi", null, null, "email-0")))
            val noDefault = EmailSender({ if (it == EmailApp.KEY_TO) null else values[it] }, client = { client }, list = list)
            assertEquals(SendResult.Refused(EmailApp.NO_DEFAULT), noDefault.send(Delivery("c4", "hi", null, null)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aContactNeedsANameAndAnAddressAndGetsAnIdOfItsOwn() {
        val sam = EmailApp.contactRouter(mapOf("name" to "Sam", "address" to "sam@example.net"))
        assertEquals("Email Sam", sam.label)
        assertEquals(mapOf("address" to "sam@example.net"), sam.values)
        assertTrue(RouterIds.isRouterId(sam.id) && sam.id.startsWith("email-"))
        assertTrue(sam.id != EmailApp.contactRouter(mapOf("name" to "Sam", "address" to "sam@example.net")).id)
        assertFailsWith<IllegalArgumentException> { EmailApp.contactRouter(mapOf("name" to "Sam", "address" to "sam@example.net>\r\nBcc: x@y")) }
        assertFailsWith<IllegalArgumentException> { EmailApp.contactRouter(mapOf("name" to "", "address" to "sam@example.net")) }
    }

    @Test
    fun aCertificateTheClientDoesNotAcceptIsARefusalAndAServerOutOfReachIsTriedAgain() {
        assertEquals(
            SendResult.Refused("The mail server's certificate was not accepted: the server's certificate is not the pinned one"),
            sender(SSLHandshakeException("the server's certificate is not the pinned one")).send(Delivery("c1", "hello", null, null)),
        )
        assertEquals(SendResult.Refused("The mail server refused it: 550 no"), sender(SmtpRefusedException("550 no")).send(Delivery("c1", "hello", null, null)))
        assertIs<SendResult.Retry>(sender(IOException("connect timed out")).send(Delivery("c1", "hello", null, null)))
    }
}
