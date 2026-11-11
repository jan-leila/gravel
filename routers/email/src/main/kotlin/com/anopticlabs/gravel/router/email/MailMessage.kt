package com.anopticlabs.gravel.router.email

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Builds the one kind of message this app sends: plain text, UTF-8, base64 so no line is too long and no byte is unsafe. */
object MailMessage {
    /**
     * [messageId] is the capture id under this app's domain, so a delivery Gravel repeats is the
     * same message to a mailbox that de-duplicates on it; [date] is the recording's start when
     * granted, else [now].
     */
    fun build(from: String, to: String, subject: String, body: String, messageId: String, date: ZonedDateTime): ByteArray {
        val lines = listOf(
            "From: ${address(from)}",
            "To: ${address(to)}",
            "Subject: ${encodedWord(subject)}",
            "Date: ${date.format(DateTimeFormatter.RFC_1123_DATE_TIME)}",
            "Message-ID: <$messageId@gravel.router>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=utf-8",
            "Content-Transfer-Encoding: base64",
            "",
        ) + Base64.getMimeEncoder(76, "\r\n".encodeToByteArray()).encodeToString(body.encodeToByteArray()).split("\r\n")
        return lines.joinToString("\r\n").encodeToByteArray()
    }

    /** The address up to the first space, control character or bracket, so a setting cannot inject a header. */
    fun address(value: String): String {
        val bare = value.trim().removePrefix("<").takeWhile { !it.isISOControl() && !it.isWhitespace() && it != '<' && it != '>' }
        return "<$bare>"
    }

    /** The subject as is when it is plain ASCII, else RFC 2047's base64 encoded word, so a non-Latin subject survives. */
    fun encodedWord(subject: String): String {
        val cleaned = subject.filterNot { it.isISOControl() }
        return if (cleaned.all { it.code in 32..126 }) cleaned else "=?UTF-8?B?${Base64.getEncoder().encodeToString(cleaned.encodeToByteArray())}?="
    }
}
