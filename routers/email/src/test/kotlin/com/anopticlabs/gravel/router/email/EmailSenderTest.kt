package com.anopticlabs.gravel.router.email

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.SettingValues
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
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
