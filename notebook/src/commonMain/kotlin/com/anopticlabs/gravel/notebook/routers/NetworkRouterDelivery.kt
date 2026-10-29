package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The body a Network router posts: the router API's payload under its snake-case names (`ROUTER_API.md`). */
@Serializable
data class NetworkPayload(
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("capture_id") val captureId: String,
    @SerialName("recorded_at") val recordedAt: String,
    val text: String,
) {
    companion object {
        fun of(text: RoutedText): NetworkPayload = NetworkPayload(RouterApi.VERSION, text.captureId, text.recordedAt, text.text)
    }
}

/**
 * Delivers to a Network router: one POST of the payload to its address, the token as a bearer
 * token. A 2xx is taken; a 4xx other than 408 and 429 would come back the same way and flags the
 * capture; no network, or a server that cannot be reached, makes the capture wait for the
 * network; anything else is tried again.
 */
class NetworkRouterDelivery(
    private val poster: TextPoster,
    private val hasNetwork: () -> Boolean,
) : TextDeliverer {
    override suspend fun deliver(router: RouterConfig, text: RoutedText) {
        require(router is RouterConfig.Network) { "${router.name} is not a network router" }
        if (!RoutingConfig.isHttpsUrl(router.url)) unavailable("${router.name} has no https:// address")
        if (!hasNetwork()) waitForNetwork("${router.name} waits for a network")
        val body = NotebookJson.encodeToString(NetworkPayload.serializer(), NetworkPayload.of(text))
        when (val result = poster.post(router.url, router.bearerToken, body)) {
            is PostResult.Answered -> when (result.status) {
                in 200..299 -> Unit
                408, 429 -> retry("${router.name} answered HTTP ${result.status}")
                in 400..499 -> unavailable("${router.name} refused the text (HTTP ${result.status})")
                else -> retry("${router.name} answered HTTP ${result.status}")
            }
            PostResult.NotConnected -> waitForNetwork("${router.name} could not be reached")
            is PostResult.Failed -> retry("${router.name} failed: ${result.reason}")
        }
    }

    private fun unavailable(why: String): Nothing =
        throw CaptureProcessingException(CaptureFailureKind.FLAG, why, reason = CaptureFailureReason.ROUTER_UNAVAILABLE)

    private fun waitForNetwork(why: String): Nothing = throw CaptureProcessingException(CaptureFailureKind.WAIT, why, waitsForNetwork = true)

    private fun retry(why: String): Nothing = throw CaptureProcessingException(CaptureFailureKind.RETRY, why)
}
