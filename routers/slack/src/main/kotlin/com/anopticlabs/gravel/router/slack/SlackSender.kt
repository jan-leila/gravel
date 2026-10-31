package com.anopticlabs.gravel.router.slack

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException

/** The webhook post for a delivery: Slack's incoming webhooks take `{"text": ...}` and answer "ok". */
fun slackRequest(webhook: String, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = webhook,
    body = """{"text":${jsonString(delivery.text)}}""".encodeToByteArray(),
    contentType = "application/json",
)

/**
 * A 2xx is sent. A 4xx is a refusal: the webhook is gone or wrong, which no retry changes, and the
 * user has to fix the setting. Anything else, including a network failure, is tried again.
 */
class SlackSender(private val settings: Settings, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val webhook = settings[SlackApp.KEY_WEBHOOK] ?: return SendResult.Refused("The Slack router is not set up")
        if (!Http.allowed(webhook, allowPrivateCleartext = false)) return SendResult.Refused(SlackApp.BAD_ADDRESS)
        return answer(try {
            send(slackRequest(webhook, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("Slack could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when {
            answer.ok -> SendResult.Sent
            answer.status == 408 || answer.status == 429 -> SendResult.Retry("Slack answered HTTP ${answer.status}")
            answer.status in 400..499 -> SendResult.Refused("Slack refused the webhook (HTTP ${answer.status})")
            else -> answer.addressReason("Slack")?.let(SendResult::Refused) ?: SendResult.Retry("Slack answered HTTP ${answer.status}")
        }
    }
}
