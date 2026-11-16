package com.anopticlabs.gravel.app

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.pipeline.CaptureRemedy
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.textserver.SelfHostedTextService
import com.anopticlabs.gravel.textserver.TextServerException
import com.anopticlabs.gravel.textserver.TextServerMode
import coredevices.util.transcription.TransportFailure
import kotlinx.coroutines.CancellationException

/** The user's text model server as [RoutedNotebookText] uses it; [generate] fails as [TextServerException]. */
interface NotebookServerText {
    fun mode(): TextServerMode

    fun configured(): Boolean

    fun allowedOnCurrentNetwork(): Boolean

    suspend fun generate(system: String, user: String, maxTokens: Int): String
}

class SelfHostedServerText(private val service: SelfHostedTextService) : NotebookServerText {
    override fun mode() = service.mode()

    override fun configured() = service.configured()

    override fun allowedOnCurrentNetwork() = service.allowedOnCurrentNetwork()

    override suspend fun generate(system: String, user: String, maxTokens: Int) = service.generate(system, user, maxTokens)
}

/** Where one text call runs under the setting: the phone, the server, or one and then the other for what it fails. */
enum class TextRoute { PHONE, SERVER, SERVER_THEN_PHONE, PHONE_THEN_SERVER }

/**
 * The route for [mode]. A fallback mode without a configured server is the phone's, as the speech modes are; a
 * fallback mode whose phone has no usable model sends to the server, and one off the allowed network keeps to the
 * phone when the phone can run, so a capture never waits on Wi-Fi for a step the phone can take.
 */
fun textRoute(mode: TextServerMode, serverConfigured: Boolean, serverAllowed: Boolean, phoneReady: Boolean): TextRoute = when (mode) {
    TextServerMode.LocalOnly -> TextRoute.PHONE
    TextServerMode.ServerOnly -> TextRoute.SERVER
    TextServerMode.ServerFirst -> when {
        !serverConfigured -> TextRoute.PHONE
        !phoneReady -> TextRoute.SERVER
        serverAllowed -> TextRoute.SERVER_THEN_PHONE
        else -> TextRoute.PHONE
    }
    TextServerMode.LocalFirst -> when {
        !serverConfigured -> TextRoute.PHONE
        !phoneReady -> TextRoute.SERVER
        serverAllowed -> TextRoute.PHONE_THEN_SERVER
        else -> TextRoute.PHONE
    }
}

internal const val NO_TEXT_SERVER_HELD = "no text model server is set up"
internal const val TEXT_SERVER_WIFI_HELD = "waiting for Wi-Fi to reach your text model server"
internal const val TEXT_SERVER_UNREACHABLE_HELD = "your text model server can't be reached"
internal const val TEXT_SERVER_CERTIFICATE_HELD = "your text model server's certificate isn't trusted; check it in the server's settings"

/**
 * A server failure as the capture drain acts on it. What every capture would meet (no server, no allowed network,
 * a server that cannot be reached or whose certificate is refused, a request the server refuses with a 4xx such as a
 * wrong model name or token) holds the drain; a connection lost or timed out, a server error or a reply that is not
 * a chat completion counts an attempt for this capture.
 */
internal fun serverTextFailure(e: TextServerException): CaptureProcessingException = when (e) {
    is TextServerException.NotConfigured ->
        CaptureProcessingException(CaptureFailureKind.HOLD, NO_TEXT_SERVER_HELD, e, remedy = CaptureRemedy.TEXT_MODEL)
    is TextServerException.NotAllowedOnNetwork ->
        CaptureProcessingException(CaptureFailureKind.HOLD, TEXT_SERVER_WIFI_HELD, e, waitsForNetwork = true)
    is TextServerException.Unreachable -> when (e.failure) {
        TransportFailure.NOT_CONNECTED -> CaptureProcessingException(CaptureFailureKind.HOLD, TEXT_SERVER_UNREACHABLE_HELD, e, waitsForNetwork = true)
        TransportFailure.CERTIFICATE -> CaptureProcessingException(CaptureFailureKind.HOLD, TEXT_SERVER_CERTIFICATE_HELD, e, remedy = CaptureRemedy.TEXT_MODEL)
        TransportFailure.TIMED_OUT, TransportFailure.LOST ->
            CaptureProcessingException(CaptureFailureKind.RETRY, "the connection to the text model server was lost", e)
    }
    is TextServerException.Failed -> if (e.status in 400..499) {
        CaptureProcessingException(CaptureFailureKind.HOLD, "your text model server refused the request (HTTP ${e.status})", e, remedy = CaptureRemedy.TEXT_MODEL)
    } else {
        CaptureProcessingException(CaptureFailureKind.RETRY, "your text model server could not answer", e)
    }
}

/**
 * Gravel: the Notebook's text step under the text model setting: the phone's engine ([phone], the [NotebookText] the
 * Notebook always had), the user's server, or one with the other as fallback, decided per call by [textRoute]. A
 * fallback is taken for any failure of the first step but a cancel, and when both fail the first step's failure is
 * reported. The server needs no load, so [prepare] loads the phone's model only on routes that start on the phone,
 * and [release] always frees it.
 */
class RoutedNotebookText(private val phone: CaptureText, private val server: NotebookServerText) : CaptureText {
    private suspend fun phoneReady(): Boolean = try {
        phone.checkReady()
        true
    } catch (e: CaptureProcessingException) {
        false
    }

    private suspend fun route(): TextRoute {
        val mode = server.mode()
        if (mode == TextServerMode.LocalOnly) return TextRoute.PHONE
        if (mode == TextServerMode.ServerOnly) return TextRoute.SERVER
        return textRoute(mode, server.configured(), server.allowedOnCurrentNetwork(), phoneReady())
    }

    override suspend fun checkReady() = when (route()) {
        TextRoute.PHONE -> phone.checkReady()
        TextRoute.SERVER -> checkServer()
        TextRoute.SERVER_THEN_PHONE, TextRoute.PHONE_THEN_SERVER -> Unit
    }

    private fun checkServer() {
        if (!server.configured()) throw serverTextFailure(TextServerException.NotConfigured())
        if (!server.allowedOnCurrentNetwork()) throw serverTextFailure(TextServerException.NotAllowedOnNetwork())
    }

    override suspend fun prepare() = when (route()) {
        TextRoute.PHONE -> phone.prepare()
        // A load that fails here is the phone step's failure, which the server step then takes over from.
        TextRoute.PHONE_THEN_SERVER -> try {
            phone.prepare()
        } catch (e: CaptureProcessingException) {
            logger.w { "text model not loaded, the server will answer: ${e.cause?.let { it::class.simpleName }}" }
        }
        TextRoute.SERVER, TextRoute.SERVER_THEN_PHONE -> Unit
    }

    override suspend fun generate(system: String, user: String, maxTokens: Int): String = when (route()) {
        TextRoute.PHONE -> phone.generate(system, user, maxTokens)
        TextRoute.SERVER -> onServer(system, user, maxTokens)
        TextRoute.SERVER_THEN_PHONE -> try {
            onServer(system, user, maxTokens)
        } catch (e: CaptureProcessingException) {
            logger.w { "text server failed, the phone answers: ${e.cause?.let { it::class.simpleName }}" }
            try {
                phone.generate(system, user, maxTokens)
            } catch (phoneFailure: CaptureProcessingException) {
                throw e
            }
        }
        TextRoute.PHONE_THEN_SERVER -> try {
            phone.generate(system, user, maxTokens)
        } catch (e: CaptureProcessingException) {
            logger.w { "text model failed, the server answers: ${e.cause?.let { it::class.simpleName }}" }
            try {
                onServer(system, user, maxTokens)
            } catch (serverFailure: CaptureProcessingException) {
                throw e
            }
        }
    }

    private suspend fun onServer(system: String, user: String, maxTokens: Int): String = try {
        server.generate(system, user, maxTokens)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TextServerException) {
        throw serverTextFailure(e)
    }

    override suspend fun release() = phone.release()

    private companion object {
        val logger = Logger.withTag("RoutedNotebookText")
    }
}
