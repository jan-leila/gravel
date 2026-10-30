package com.anopticlabs.gravel.router.signal

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException

/** The message: signal-cli-rest-api's `POST /v2/send` with the text, the sender's number and the recipients. */
fun signalRequest(relay: String, token: String?, number: String, recipients: List<String>, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = relay.trimEnd('/') + "/v2/send",
    headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
    body = """{"message":${jsonString(delivery.text)},"number":${jsonString(number)},"recipients":[${recipients.joinToString(",") { jsonString(it) }}]}""".encodeToByteArray(),
    contentType = "application/json",
)

/** The recipients as typed, one per comma, blanks dropped. */
fun signalRecipients(typed: String): List<String> = typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }

class SignalSender(private val settings: Settings, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val relay = settings[SignalApp.KEY_URL] ?: return SendResult.Refused("The Signal router is not set up")
        val number = settings[SignalApp.KEY_NUMBER] ?: return SendResult.Refused("The Signal router has no sender number")
        val recipients = signalRecipients(settings[SignalApp.KEY_RECIPIENTS].orEmpty())
        if (recipients.isEmpty()) return SendResult.Refused("The Signal router has no recipients")
        if (!Http.allowed(relay, allowPrivateCleartext = true)) return SendResult.Refused("The relay address has to be https://, or http:// on your own network")
        return answer(try {
            send(signalRequest(relay, settings[SignalApp.KEY_TOKEN], number, recipients, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("The Signal relay could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            400 -> SendResult.Refused("The Signal relay refused the message, a number perhaps (HTTP 400)")
            401, 403 -> SendResult.Refused("The Signal relay refused the token (HTTP ${answer.status})")
            else -> SendResult.Retry("The Signal relay answered HTTP ${answer.status}")
        }
    }
}
