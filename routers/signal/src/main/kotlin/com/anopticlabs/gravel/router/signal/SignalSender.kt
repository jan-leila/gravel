package com.anopticlabs.gravel.router.signal

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.ListedRouter
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SettingValues
import com.anopticlabs.gravel.router.common.fetchJson
import com.anopticlabs.gravel.router.common.string
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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

/** The contacts of the relay's account. */
fun signalContactsRequest(relay: String, token: String?, number: String): HttpRequest = HttpRequest(
    method = "GET",
    url = relay.trimEnd('/') + "/v1/contacts/" + URLEncoder.encode(number.trim(), "UTF-8"),
    headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
    answerLimit = Http.LIST_BODY_BYTES,
)

/**
 * The routers of the relay's contacts, each sent to by its number, or by its account id when the
 * contact shows no number; named by what the user called the contact, else the contact's own
 * profile name, else its username or number. A contact with neither number nor id is left out.
 */
@Throws(IOException::class)
fun signalContacts(relay: String, token: String?, number: String, send: (HttpRequest) -> HttpAnswer): List<ListedRouter> {
    if (!Http.allowed(relay, allowPrivateCleartext = true)) throw IOException(SignalApp.BAD_ADDRESS)
    val contacts = fetchJson("The Signal relay", send, signalContactsRequest(relay, token, number)) as? JsonArray ?: throw IOException("The Signal relay did not answer with a list")
    return contacts.mapNotNull { element ->
        val contact = element as? JsonObject ?: return@mapNotNull null
        val recipient = contact.string("number") ?: contact.string("uuid") ?: return@mapNotNull null
        val profile = contact["profile"] as? JsonObject
        val profileName = listOfNotNull(profile?.string("given_name"), profile?.string("lastname")).joinToString(" ").ifEmpty { null }
        val name = contact.string("name") ?: contact.string("profile_name") ?: profileName ?: contact.string("username") ?: recipient
        ListedRouter(
            id = RouterIds.of(SignalApp.LISTED_PREFIX, contact.string("uuid") ?: recipient),
            label = "Signal $name",
            description = "Sends what you said to $name over Signal",
            values = mapOf(SignalApp.VALUE_RECIPIENT to recipient),
        )
    }
}

class SignalSender(private val settings: SettingValues, private val list: RouterList, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val relay = settings[SignalApp.KEY_URL] ?: return SendResult.Refused("The Signal router is not set up")
        val number = settings[SignalApp.KEY_NUMBER] ?: return SendResult.Refused("The Signal router has no sender number")
        val recipients = when (val id = delivery.routerId) {
            null -> signalRecipients(settings[SignalApp.KEY_RECIPIENTS].orEmpty())
            else -> listOf(list.find(id)?.values?.get(SignalApp.VALUE_RECIPIENT) ?: return SendResult.Refused(RouterReceiver.NOT_LISTED))
        }
        if (recipients.isEmpty()) return SendResult.Refused(SignalApp.NO_RECIPIENTS)
        if (!Http.allowed(relay, allowPrivateCleartext = true)) return SendResult.Refused(SignalApp.BAD_ADDRESS)
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
            else -> answer.addressReason("The Signal relay")?.let(SendResult::Refused) ?: SendResult.Retry("The Signal relay answered HTTP ${answer.status}")
        }
    }
}
