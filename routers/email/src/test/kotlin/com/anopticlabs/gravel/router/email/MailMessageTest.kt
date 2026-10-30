package com.anopticlabs.gravel.router.email

import java.time.ZonedDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailMessageTest {

    @Test
    fun theMessageHasItsHeadersAndABase64Body() {
        val text = MailMessage.build("me@example.net", "you@example.net", "Voice note 2026-09-22T17:00:00+02:00", "café 😀 line\r\ntwo", "c1", ZonedDateTime.parse("2026-09-22T17:00:00+02:00")).decodeToString()
        val (headers, body) = text.split("\r\n\r\n", limit = 2)
        assertEquals(
            listOf(
                "From: <me@example.net>", "To: <you@example.net>", "Subject: Voice note 2026-09-22T17:00:00+02:00", "Date: Tue, 22 Sep 2026 17:00:00 +0200",
                "Message-ID: <c1@gravel.router>", "MIME-Version: 1.0", "Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: base64",
            ),
            headers.split("\r\n"),
        )
        assertEquals("café 😀 line\r\ntwo", Base64.getMimeDecoder().decode(body).decodeToString())
    }

    @Test
    fun headersCannotBeInjectedThroughAnAddressOrASubject() {
        assertEquals("<me@example.net>", MailMessage.address("me@example.net\r\nBcc: x@y"))
        assertEquals("<me@example.net>", MailMessage.address("<me@example.net>"))
        assertEquals("Voice note", MailMessage.encodedWord("Voice\r\n note"))
        assertEquals("=?UTF-8?B?U3ByYWNobm90aXogw7xiZXIgw4RwZmVs?=", MailMessage.encodedWord("Sprachnotiz über Äpfel"))
    }

    @Test
    fun theSubjectTheDateAndThePinFollowTheSettingsAndTheGrant() {
        assertEquals("Voice note", emailSubject(null, null))
        assertEquals("Voice note 2026-09-22T17:00:00+02:00", emailSubject(" ", "2026-09-22T17:00:00+02:00"))
        assertEquals("Memo 2026-09-22T17:00:00+02:00", emailSubject("Memo", "2026-09-22T17:00:00+02:00"))
        val now = ZonedDateTime.parse("2026-10-30T10:00:00Z")
        assertEquals(ZonedDateTime.parse("2026-09-22T17:00:00+02:00"), emailDate("2026-09-22T17:00:00+02:00", now))
        assertEquals(now, emailDate(null, now))
        assertEquals(now, emailDate("yesterday", now))
        assertEquals("a".repeat(64), normalizePin("AA:".repeat(32).trimEnd(':')))
        assertEquals("0123456789abcdef".repeat(4), normalizePin(" 0123456789ABCDEF".repeat(4)))
        assertNull(normalizePin(""))
        assertNull(normalizePin(null))
        assertNull(normalizePin("abc"))
        assertTrue(normalizePin("zz".repeat(32)) == null)
    }
}
