package com.anopticlabs.gravel.router.homeassistant

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import com.anopticlabs.gravel.router.common.jsonString
import java.io.IOException
import java.util.Locale

/** The Assist call: Home Assistant's REST API takes the sentence at `/api/conversation/process` with a bearer token. */
fun homeAssistantRequest(baseUrl: String, token: String, language: String, delivery: Delivery): HttpRequest = HttpRequest(
    method = "POST",
    url = baseUrl.trimEnd('/') + "/api/conversation/process",
    headers = mapOf("Authorization" to "Bearer $token"),
    body = """{"text":${jsonString(delivery.text)},"language":${jsonString(language)}}""".encodeToByteArray(),
    contentType = "application/json",
)

/**
 * A 2xx is sent: Assist took the sentence, whatever it made of it. A 401 or 403 is a refusal
 * (the token), as is a 404 (no conversation API at that address); the rest is tried again.
 */
class HomeAssistantSender(
    private val settings: Settings,
    private val send: (HttpRequest) -> HttpAnswer,
    private val defaultLanguage: () -> String = { Locale.getDefault().language.ifEmpty { "en" } },
) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val url = settings[HomeAssistantApp.KEY_URL] ?: return SendResult.Refused("The Home Assistant router is not set up")
        val token = settings[HomeAssistantApp.KEY_TOKEN] ?: return SendResult.Refused("The Home Assistant router has no token")
        if (!Http.allowed(url, allowPrivateCleartext = true)) return SendResult.Refused(HomeAssistantApp.BAD_ADDRESS)
        val language = settings[HomeAssistantApp.KEY_LANGUAGE] ?: defaultLanguage()
        return answer(try {
            send(homeAssistantRequest(url, token, language, delivery))
        } catch (e: IOException) {
            return SendResult.Retry("Home Assistant could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            401, 403 -> SendResult.Refused("Home Assistant refused the token (HTTP ${answer.status})")
            404 -> SendResult.Refused("Home Assistant has no Assist at that address (HTTP 404)")
            else -> answer.addressReason("Home Assistant")?.let(SendResult::Refused) ?: SendResult.Retry("Home Assistant answered HTTP ${answer.status}")
        }
    }
}
