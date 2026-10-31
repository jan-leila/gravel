package com.anopticlabs.gravel.router.mastodon

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import java.io.IOException
import java.net.URLEncoder

/**
 * The status: a form post to `/api/v1/statuses` with the text and the visibility, the capture id as
 * the idempotency key, so a delivery Gravel repeats does not post twice.
 */
fun mastodonRequest(baseUrl: String, token: String, visibility: String, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = baseUrl.trimEnd('/') + "/api/v1/statuses",
    headers = mapOf("Authorization" to "Bearer $token", "Idempotency-Key" to delivery.captureId),
    body = "status=${form(delivery.text)}&visibility=${form(visibility)}".encodeToByteArray(),
    contentType = "application/x-www-form-urlencoded",
)

private fun form(value: String): String = URLEncoder.encode(value, "UTF-8")

class MastodonSender(private val settings: Settings, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val url = settings[MastodonApp.KEY_URL] ?: return SendResult.Refused("The Mastodon router is not set up")
        val token = settings[MastodonApp.KEY_TOKEN] ?: return SendResult.Refused("The Mastodon router has no token")
        if (!Http.allowed(url, allowPrivateCleartext = false)) return SendResult.Refused(MastodonApp.BAD_ADDRESS)
        val visibility = settings[MastodonApp.KEY_VISIBILITY]?.takeIf { it in MastodonApp.VISIBILITIES } ?: MastodonApp.VISIBILITIES.first()
        return answer(try {
            send(mastodonRequest(url, token, visibility, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("Mastodon could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            401, 403 -> SendResult.Refused("Mastodon refused the token (HTTP ${answer.status})")
            422 -> SendResult.Refused("Mastodon refused the status, too long perhaps (HTTP 422)")
            else -> SendResult.Retry("Mastodon answered HTTP ${answer.status}")
        }
    }
}
