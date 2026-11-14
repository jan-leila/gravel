package com.anopticlabs.gravel.router.email

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.SettingValues
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLHandshakeException

/** What the settings say about the server, read once per send. */
data class SmtpAccount(
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val username: String,
    val password: String,
    val from: String,
    val subject: String,
    val pinnedSha256: String?,
)

/** The subject line: the user's prefix (or "Voice note") and, when granted, the recording's start. */
fun emailSubject(prefix: String?, recordedAt: String?): String {
    val base = prefix?.trim()?.ifEmpty { null } ?: "Voice note"
    return if (recordedAt == null) base else "$base $recordedAt"
}

/** The message's date: the recording's start when granted and readable, else [now]. */
fun emailDate(recordedAt: String?, now: ZonedDateTime): ZonedDateTime = recordedAt?.let {
    try {
        ZonedDateTime.parse(it)
    } catch (e: DateTimeParseException) {
        null
    }
} ?: now

/** The pin as typed, normalized to lowercase hex without separators, or null when it is empty; anything else is not a digest. */
fun normalizePin(typed: String?): String? {
    val cleaned = typed.orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
    return cleaned.takeIf { it.length == 64 }
}

/**
 * The address a delivery goes to, or the reason it has none: the declared router's default
 * recipient, or the address of the listed contact. Every router of this app is a case here, so a
 * router that picks the recipient from the words is added here too.
 */
fun emailRecipient(delivery: Delivery, defaultTo: String?, list: RouterList?): Result<String> = when (val id = delivery.routerId) {
    null -> defaultTo?.let { Result.success(it) } ?: Result.failure(IllegalStateException(EmailApp.NO_DEFAULT))
    else -> list?.find(id)?.values?.get(EmailApp.VALUE_ADDRESS)?.let { Result.success(it) } ?: Result.failure(IllegalStateException(RouterReceiver.NOT_LISTED))
}

class EmailSender(
    private val settings: SettingValues,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
    private val client: (SmtpAccount) -> SmtpClient = { SmtpClient(it.host, it.port, it.security, it.pinnedSha256) },
    private val list: RouterList? = null,
) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val account = account() ?: return SendResult.Refused("The email router is not set up")
        val to = emailRecipient(delivery, settings[EmailApp.KEY_TO], list).getOrElse { return SendResult.Refused(it.message.orEmpty()) }
        val pinTyped = settings[EmailApp.KEY_PIN]
        if (!pinTyped.isNullOrBlank() && account.pinnedSha256 == null) return SendResult.Refused("The server certificate SHA-256 is not a 64-digit hex digest")
        val message = MailMessage.build(
            from = account.from,
            to = to,
            subject = delivery.field("subject") ?: emailSubject(account.subject, delivery.recordedAt),
            body = delivery.field("body") ?: delivery.content,
            messageId = delivery.captureId,
            date = emailDate(delivery.recordedAt, now()),
        )
        return try {
            client(account).send(account.username, account.password, account.from, to, message)
            SendResult.Sent
        } catch (e: SmtpRefusedException) {
            SendResult.Refused("The mail server refused it: ${e.message}")
        } catch (e: SSLHandshakeException) {
            // The pin no longer matches, or the certificate is one the phone does not trust: no retry changes that.
            SendResult.Refused("The mail server's certificate was not accepted: ${e.message ?: e::class.simpleName}")
        } catch (e: IOException) {
            SendResult.Retry("The mail server could not be reached: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun account(): SmtpAccount? {
        val port = settings[EmailApp.KEY_PORT]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return SmtpAccount(
            host = settings[EmailApp.KEY_HOST] ?: return null,
            port = port,
            security = if (settings[EmailApp.KEY_SECURITY] == "TLS") SmtpSecurity.TLS else SmtpSecurity.STARTTLS,
            username = settings[EmailApp.KEY_USERNAME] ?: return null,
            password = settings[EmailApp.KEY_PASSWORD] ?: return null,
            from = settings[EmailApp.KEY_FROM] ?: return null,
            subject = settings[EmailApp.KEY_SUBJECT].orEmpty(),
            pinnedSha256 = normalizePin(settings[EmailApp.KEY_PIN]),
        )
    }
}
