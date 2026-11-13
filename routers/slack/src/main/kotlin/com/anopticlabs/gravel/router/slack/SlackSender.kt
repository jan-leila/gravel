package com.anopticlabs.gravel.router.slack

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.ListedRouter
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterReceiver
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.SettingValues
import com.anopticlabs.gravel.router.common.fetchJson
import com.anopticlabs.gravel.router.common.jsonString
import com.anopticlabs.gravel.router.common.string
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The webhook post for a delivery: Slack's incoming webhooks take `{"text": ...}` and answer "ok". */
fun slackRequest(webhook: String, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = webhook,
    body = """{"text":${jsonString(delivery.field("text") ?: delivery.content)}}""".encodeToByteArray(),
    contentType = "application/json",
)

/** The bot's post to a channel: `chat.postMessage`, which answers 200 with `ok` false and an error code when it does not post. */
fun slackPostRequest(token: String, channel: String, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = "$SLACK_API/chat.postMessage",
    headers = mapOf("Authorization" to "Bearer $token"),
    body = """{"channel":${jsonString(channel)},"text":${jsonString(delivery.field("text") ?: delivery.content)}}""".encodeToByteArray(),
    contentType = "application/json; charset=utf-8",
)

/** One page of the channels the bot is a member of, public and private, archived ones left out. */
fun slackChannelsRequest(token: String, cursor: String?): HttpRequest = HttpRequest(
    method = "GET",
    url = "$SLACK_API/users.conversations?types=public_channel,private_channel&exclude_archived=true&limit=200" +
        cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") }.orEmpty(),
    headers = mapOf("Authorization" to "Bearer $token"),
    answerLimit = Http.LIST_BODY_BYTES,
)

/** Every channel the bot is in, following Slack's cursor up to [MAX_PAGES] pages; an `ok` false answer is an IOException with its error code. */
@Throws(IOException::class)
fun slackChannels(token: String, send: (HttpRequest) -> HttpAnswer): List<ListedRouter> {
    val routers = mutableListOf<ListedRouter>()
    var cursor: String? = null
    repeat(MAX_PAGES) {
        val answer = fetchJson("Slack", send, slackChannelsRequest(token, cursor)) as? JsonObject ?: throw IOException("Slack did not answer with a list")
        if ((answer["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException("Slack refused the list: ${answer.string("error") ?: "no reason given"}")
        (answer["channels"] as? JsonArray).orEmpty().forEach { element ->
            val channel = element as? JsonObject ?: return@forEach
            val id = channel.string("id") ?: return@forEach
            val name = channel.string("name") ?: id
            routers += ListedRouter(
                id = RouterIds.of(SlackApp.LISTED_PREFIX, id),
                label = "Slack #$name",
                description = "Posts what you said to #$name",
                values = mapOf(SlackApp.VALUE_CHANNEL to id),
            )
        }
        cursor = ((answer["response_metadata"] as? JsonObject)?.string("next_cursor")) ?: return routers
    }
    return routers
}

private const val SLACK_API = "https://slack.com/api"
private const val MAX_PAGES = 10

/**
 * A 2xx is sent. A 4xx is a refusal: the webhook is gone or wrong, which no retry changes, and the
 * user has to fix the setting. Anything else, including a network failure, is tried again. A post
 * to a listed channel is read by [postAnswer].
 */
class SlackSender(private val settings: SettingValues, private val list: RouterList, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        delivery.routerId?.let { id ->
            val token = settings[SlackApp.KEY_BOT_TOKEN] ?: return SendResult.Refused(SlackApp.NO_BOT_TOKEN)
            val channel = list.find(id)?.values?.get(SlackApp.VALUE_CHANNEL) ?: return SendResult.Refused(RouterReceiver.NOT_LISTED)
            return postAnswer(try {
                send(slackPostRequest(token, channel, delivery))
            } catch (e: IOException) {
                return SendResult.Retry("Slack could not be reached: ${e::class.simpleName}")
            })
        }
        val webhook = settings[SlackApp.KEY_WEBHOOK] ?: return SendResult.Refused("The Slack router is not set up")
        if (!Http.allowed(webhook, allowPrivateCleartext = false)) return SendResult.Refused(SlackApp.BAD_ADDRESS)
        return answer(try {
            send(slackRequest(webhook, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("Slack could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        // Slack's error codes for a failure on its side; every other code (not_in_channel, invalid_auth, channel_not_found...) is the setup.
        private val TRANSIENT = setOf("ratelimited", "internal_error", "fatal_error", "service_unavailable", "request_timeout")

        fun answer(answer: HttpAnswer): SendResult = when {
            answer.ok -> SendResult.Sent
            answer.status == 408 || answer.status == 429 -> SendResult.Retry("Slack answered HTTP ${answer.status}")
            answer.status in 400..499 -> SendResult.Refused("Slack refused the webhook (HTTP ${answer.status})")
            else -> answer.addressReason("Slack")?.let(SendResult::Refused) ?: SendResult.Retry("Slack answered HTTP ${answer.status}")
        }

        fun postAnswer(answer: HttpAnswer): SendResult {
            if (!answer.ok) return if (answer.status == 429 || answer.status >= 500) SendResult.Retry("Slack answered HTTP ${answer.status}") else SendResult.Refused("Slack refused the post (HTTP ${answer.status})")
            val body = try {
                Json.parseToJsonElement(answer.body) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return SendResult.Retry("Slack's answer was not readable")
            if ((body["ok"] as? JsonPrimitive)?.booleanOrNull == true) return SendResult.Sent
            val error = body.string("error") ?: "no reason given"
            return if (error in TRANSIENT) SendResult.Retry("Slack answered $error") else SendResult.Refused("Slack refused the post: $error")
        }
    }
}
