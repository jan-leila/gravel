package com.anopticlabs.gravel.textserver

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.network.NotOnAllowedNetworkException
import com.anopticlabs.gravel.network.ServerClientCache
import com.anopticlabs.gravel.network.ServerTrustStore
import com.anopticlabs.gravel.network.UnmeteredWifi
import com.anopticlabs.gravel.network.readBoundedBody
import coredevices.util.CoreConfigFlow
import coredevices.util.transcription.MAX_CAUSE_DEPTH
import coredevices.util.transcription.SelfHostedClientFactory
import coredevices.util.transcription.ServerUnreachableException
import coredevices.util.transcription.TransportFailure
import coredevices.util.transcription.describeTransportFailure
import coredevices.util.transcription.serverHostPort
import coredevices.util.transcription.transportFailure
import coredevices.util.transcription.validateServerUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Gravel: why a request to the text model server gave no reply; messages never name the server's address. */
sealed class TextServerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No valid URL or no model name is saved. */
    class NotConfigured : TextServerException("no text model server is set up")

    /** The Wi-Fi-only rule allows no network now, or stopped allowing it during the request. */
    class NotAllowedOnNetwork : TextServerException("the Wi-Fi-only rule allows no network now")

    /** The transport failed; [failure] tells a connection never made from a refused certificate or one lost. */
    class Unreachable(cause: ServerUnreachableException) : TextServerException(cause.message ?: "network error", cause) {
        val failure: TransportFailure = cause.failure
    }

    /** The server answered without a usable reply, or not within the request's limit; [status] is a refusing HTTP status. */
    class Failed(message: String, val status: Int? = null) : TextServerException(message)
}

/** Gravel: what the connection test found: the HTTP status, and whether a 2xx body was a chat completion. */
data class TextServerTestResult(val status: Int, val chatReply: Boolean)

/**
 * Gravel: the Notebook's text over the user's own OpenAI-compatible chat completions server (Ollama's `/v1`
 * endpoint, llama.cpp's server, and the like): one non-streamed POST of [chatRequestJson] to the URL as configured,
 * the bearer token saved for its `host:port` when there is one, and `choices[0].message.content` read from a reply
 * bounded to [MAX_REPLY_BYTES] ([parseChatReply]). Requests go through [clientFactory]'s certificate-pinning client
 * under the transcription server's trust rule, held to the Wi-Fi-only rule unless the config allows mobile data,
 * and fail as [TextServerException].
 */
class SelfHostedTextService(
    private val coreConfigFlow: CoreConfigFlow,
    private val store: ServerTrustStore,
    private val unmeteredWifi: UnmeteredWifi,
    private val clientFactory: SelfHostedClientFactory,
    private val requestLimit: Duration = REQUEST_LIMIT,
) {
    companion object {
        private val logger = Logger.withTag("SelfHostedTextService")

        /** A reply of [MAX_SERVER_REPLY_TOKENS] in JSON-escaped text, with room for the envelope and a reasoning field. */
        internal const val MAX_REPLY_BYTES = 64 * 1024

        /** How long a request may go without a byte: a server that does not stream sends nothing until it is done. */
        val ANSWER_WAIT = 3.minutes

        /** How long a whole request may take, however the server spaces its bytes. */
        val REQUEST_LIMIT = 5.minutes

        /** The connection test's wait, which covers the server loading the model. */
        val TEST_ANSWER_WAIT = 2.minutes

        internal const val TEST_SYSTEM = "Answer with the single word OK."
        internal const val TEST_USER = "Are you there?"
        internal const val TEST_TOKENS = 16
    }

    private class ServerReply(val status: HttpStatusCode, val body: String)

    private val config get() = coreConfigFlow.value.textServerConfig

    private val clients = ServerClientCache(clientFactory, ANSWER_WAIT, store::pinnedFingerprint)

    fun mode(): TextServerMode = config.mode

    /** Whether a valid URL and a model name are saved. */
    fun configured(): Boolean = endpointOf(config) != null

    fun allowedOnCurrentNetwork(): Boolean = allowed(config)

    private fun allowed(config: TextServerConfig) = config.serverOnMobileData || unmeteredWifi.usable.value

    private fun endpointOf(config: TextServerConfig): Pair<String, String>? {
        val url = config.serverUrl?.trim()?.takeIf { validateServerUrl(it) == null } ?: return null
        val model = config.serverModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return url to model
    }

    /** One reply to [user] under [system] of at most [maxTokens] tokens, its reasoning block taken out. */
    suspend fun generate(system: String, user: String, maxTokens: Int): String {
        // One read of the config, so the URL, model, token and network rule all belong to the same save.
        val current = config
        val (url, model) = endpointOf(current) ?: throw TextServerException.NotConfigured()
        val hostPort = serverHostPort(url) ?: throw TextServerException.NotConfigured()
        if (!allowed(current)) throw TextServerException.NotAllowedOnNetwork()
        val wifiOnly = !current.serverOnMobileData
        val body = chatRequestJson(model, system, user, maxTokens)
        // Null only when this limit's own timer ran out (kotlinx.coroutines 1.11.0, Timeout.kt, withTimeoutOrNull).
        val reply = withTimeoutOrNull(requestLimit) {
            post(clients.clientFor(hostPort, wifiOnly), url, body, store.tokenFor(hostPort), wifiOnly)
        } ?: run {
            currentCoroutineContext().ensureActive()
            throw TextServerException.Failed("the server did not answer within $requestLimit")
        }
        if (!reply.status.isSuccess()) throw TextServerException.Failed("the server returned HTTP ${reply.status.value}", reply.status.value)
        return parseChatReply(reply.body) ?: throw TextServerException.Failed("the server's reply was not a chat completion")
    }

    /**
     * The settings dialog's connection test: a tiny prompt to [url] with the dialog's unsaved [model] and [token],
     * on a client of its own that is closed here and not held to the Wi-Fi-only rule. Fails as [generate] does for
     * the transport.
     */
    suspend fun testConnection(url: String, model: String, token: String?): TextServerTestResult {
        val hostPort = serverHostPort(url) ?: throw TextServerException.NotConfigured()
        val client = clientFactory.create(hostPort, { store.pinnedFingerprint(hostPort) }, wifiOnly = false, TEST_ANSWER_WAIT)
        return try {
            val reply = post(client, url.trim(), chatRequestJson(model, TEST_SYSTEM, TEST_USER, TEST_TOKENS), token, wifiOnly = false)
            TextServerTestResult(reply.status.value, reply.status.isSuccess() && parseChatReply(reply.body) != null)
        } finally {
            client.close()
        }
    }

    private suspend fun post(client: HttpClient, url: String, body: String, token: String?, wifiOnly: Boolean): ServerReply = try {
        client.prepareRequest(url) {
            method = HttpMethod.Post
            setBody(TextContent(body, ContentType.Application.Json))
            token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
        }.execute { response ->
            val bytes = response.readBoundedBody(MAX_REPLY_BYTES) ?: throw TextServerException.Failed("the server's reply was too large")
            ServerReply(response.status, bytes.decodeToString())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TextServerException) {
        throw e
    } catch (e: Exception) {
        val refused = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is NotOnAllowedNetworkException }
        if (refused || (wifiOnly && e is IOException && !allowedOnCurrentNetwork())) throw TextServerException.NotAllowedOnNetwork()
        // By class only: the transport's own messages carry the URL, and the app log must not.
        logger.w { "text server request failed: ${e::class.simpleName}" }
        if (e is IOException) throw TextServerException.Unreachable(ServerUnreachableException(describeTransportFailure(e), transportFailure(e)))
        throw TextServerException.Failed("the request failed: ${e::class.simpleName}")
    }
}
