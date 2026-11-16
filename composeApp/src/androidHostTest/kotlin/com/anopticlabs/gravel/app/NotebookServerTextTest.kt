package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.pipeline.CaptureRemedy
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.textserver.TextServerException
import com.anopticlabs.gravel.textserver.TextServerMode
import coredevices.util.transcription.ServerUnreachableException
import coredevices.util.transcription.TransportFailure
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Where the Notebook's text step runs under the text model setting, and what each server failure does to the drain. */
class NotebookServerTextTest {

    private class FakePhone(var ready: Boolean = true, var fails: CaptureProcessingException? = null) : CaptureText {
        var generated = 0
        var prepared = 0
        var released = 0
        val notReady = CaptureProcessingException(CaptureFailureKind.HOLD, "no text model is installed", remedy = CaptureRemedy.TEXT_MODEL)

        override suspend fun checkReady() {
            if (!ready) throw notReady
        }

        override suspend fun prepare() {
            prepared++
            fails?.let { throw it }
        }

        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
            generated++
            fails?.let { throw it }
            return "phone"
        }

        override suspend fun release() {
            released++
        }
    }

    private class FakeServer(
        var mode: TextServerMode,
        var configured: Boolean = true,
        var allowed: Boolean = true,
        var fails: TextServerException? = null,
    ) : NotebookServerText {
        var generated = 0

        override fun mode() = mode
        override fun configured() = configured
        override fun allowedOnCurrentNetwork() = allowed
        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
            generated++
            fails?.let { throw it }
            return "server"
        }
    }

    private fun failure(block: suspend () -> Unit): CaptureProcessingException =
        assertFailsWith<CaptureProcessingException> { runBlocking { block() } }

    private val modelFailed = CaptureProcessingException(CaptureFailureKind.FLAG, "the text model could not answer")

    @Test
    fun eachSettingRoutesTheTextStep() {
        for (configured in listOf(true, false)) for (allowed in listOf(true, false)) for (phone in listOf(true, false)) {
            assertEquals(TextRoute.PHONE, textRoute(TextServerMode.LocalOnly, configured, allowed, phone))
            assertEquals(TextRoute.SERVER, textRoute(TextServerMode.ServerOnly, configured, allowed, phone))
        }
        for (mode in listOf(TextServerMode.ServerFirst, TextServerMode.LocalFirst)) {
            assertEquals(TextRoute.PHONE, textRoute(mode, serverConfigured = false, serverAllowed = true, phoneReady = false), "no server is the phone's")
            assertEquals(TextRoute.SERVER, textRoute(mode, serverConfigured = true, serverAllowed = false, phoneReady = false), "no model on the phone")
            assertEquals(TextRoute.PHONE, textRoute(mode, serverConfigured = true, serverAllowed = false, phoneReady = true), "off the allowed network")
        }
        assertEquals(TextRoute.SERVER_THEN_PHONE, textRoute(TextServerMode.ServerFirst, true, true, true))
        assertEquals(TextRoute.PHONE_THEN_SERVER, textRoute(TextServerMode.LocalFirst, true, true, true))
    }

    /** The default is the Notebook as it always was: the server is never asked. */
    @Test
    fun localOnlyNeverTouchesTheServer() = runBlocking {
        val phone = FakePhone(ready = false)
        val server = FakeServer(TextServerMode.LocalOnly)
        val text = RoutedNotebookText(phone, server)
        assertSame(phone.notReady, failure { text.checkReady() })
        phone.ready = true
        assertEquals("phone", text.generate("s", "u", 8))
        assertEquals(0, server.generated)
    }

    @Test
    fun serverOnlyNeedsNoPhoneModelAndLoadsNone() = runBlocking {
        val phone = FakePhone(ready = false)
        val server = FakeServer(TextServerMode.ServerOnly)
        val text = RoutedNotebookText(phone, server)
        text.checkReady()
        text.prepare()
        assertEquals("server", text.generate("s", "u", 8))
        assertEquals(0, phone.prepared)
        assertEquals(0, phone.generated)
    }

    @Test
    fun serverOnlyHoldsWithoutAServerOrOffTheAllowedNetwork() = runBlocking {
        val server = FakeServer(TextServerMode.ServerOnly, configured = false)
        val text = RoutedNotebookText(FakePhone(), server)
        val none = failure { text.checkReady() }
        assertEquals(CaptureFailureKind.HOLD, none.kind)
        assertEquals(CaptureRemedy.TEXT_MODEL, none.remedy)
        server.configured = true
        server.allowed = false
        val wifi = failure { text.checkReady() }
        assertEquals(CaptureFailureKind.HOLD, wifi.kind)
        assertTrue(wifi.waitsForNetwork)
        assertEquals(0, server.generated)
    }

    @Test
    fun serverFirstFallsBackToThePhoneAndReportsTheServerWhenBothFail() = runBlocking {
        val phone = FakePhone()
        val server = FakeServer(TextServerMode.ServerFirst, fails = TextServerException.Failed("HTTP 500", 500))
        val text = RoutedNotebookText(phone, server)
        text.prepare()
        assertEquals(0, phone.prepared, "the server goes first and needs no load")
        assertEquals("phone", text.generate("s", "u", 8))
        assertEquals(1, server.generated)
        phone.fails = modelFailed
        val both = failure { text.generate("s", "u", 8) }
        assertEquals("your text model server could not answer", both.message)
        server.fails = null
        phone.fails = null
        assertEquals("server", text.generate("s", "u", 8))
    }

    @Test
    fun localFirstFallsBackToTheServerAndReportsThePhoneWhenBothFail() = runBlocking {
        val phone = FakePhone(fails = modelFailed)
        val server = FakeServer(TextServerMode.LocalFirst)
        val text = RoutedNotebookText(phone, server)
        text.prepare()
        assertEquals(1, phone.prepared)
        assertEquals("server", text.generate("s", "u", 8))
        server.fails = TextServerException.NotAllowedOnNetwork()
        assertSame(modelFailed, failure { text.generate("s", "u", 8) })
    }

    @Test
    fun aFallbackModeIsReadyWhenEitherSideCanRun() = runBlocking {
        for (mode in listOf(TextServerMode.ServerFirst, TextServerMode.LocalFirst)) {
            val phone = FakePhone(ready = false)
            val server = FakeServer(mode)
            val text = RoutedNotebookText(phone, server)
            text.checkReady()
            assertEquals("server", text.generate("s", "u", 8), "$mode with no phone model")
            server.allowed = false
            assertTrue(failure { text.checkReady() }.waitsForNetwork, "$mode: nothing can run until the network allows the server")
            server.configured = false
            assertSame(phone.notReady, failure { text.checkReady() }, "$mode without a server is the phone's setting")
            phone.ready = true
            server.configured = true
            text.checkReady()
            assertEquals("phone", text.generate("s", "u", 8), "$mode off the allowed network")
        }
    }

    @Test
    fun releaseAlwaysFreesThePhonesModel() = runBlocking {
        for (mode in TextServerMode.entries) {
            val phone = FakePhone()
            RoutedNotebookText(phone, FakeServer(mode)).release()
            assertEquals(1, phone.released, "$mode")
        }
    }

    @Test
    fun serverFailuresAreReadByKind() {
        fun unreachable(failure: TransportFailure) = TextServerException.Unreachable(ServerUnreachableException("x", failure))
        val notConfigured = serverTextFailure(TextServerException.NotConfigured())
        assertEquals(CaptureFailureKind.HOLD to CaptureRemedy.TEXT_MODEL, notConfigured.kind to notConfigured.remedy)
        val wifi = serverTextFailure(TextServerException.NotAllowedOnNetwork())
        assertEquals(CaptureFailureKind.HOLD, wifi.kind)
        assertTrue(wifi.waitsForNetwork)
        val notConnected = serverTextFailure(unreachable(TransportFailure.NOT_CONNECTED))
        assertEquals(CaptureFailureKind.HOLD, notConnected.kind)
        assertTrue(notConnected.waitsForNetwork)
        val certificate = serverTextFailure(unreachable(TransportFailure.CERTIFICATE))
        assertEquals(CaptureFailureKind.HOLD to CaptureRemedy.TEXT_MODEL, certificate.kind to certificate.remedy)
        assertEquals(CaptureFailureKind.RETRY, serverTextFailure(unreachable(TransportFailure.TIMED_OUT)).kind)
        assertEquals(CaptureFailureKind.RETRY, serverTextFailure(unreachable(TransportFailure.LOST)).kind)
        val refused = serverTextFailure(TextServerException.Failed("HTTP 404", 404))
        assertEquals(CaptureFailureKind.HOLD to CaptureRemedy.TEXT_MODEL, refused.kind to refused.remedy)
        listOf(401, 403).forEach {
            assertEquals(CaptureFailureKind.HOLD, serverTextFailure(TextServerException.Failed("HTTP $it", it)).kind, "HTTP $it")
        }
        listOf(408, 425, 429).forEach {
            val busy = serverTextFailure(TextServerException.Failed("HTTP $it", it))
            assertEquals(CaptureFailureKind.RETRY, busy.kind, "HTTP $it")
            assertNull(busy.reason)
        }
        val tooLong = serverTextFailure(TextServerException.Failed("HTTP 413", 413))
        assertEquals(CaptureFailureKind.FLAG to CaptureFailureReason.TEXT_TOO_LONG, tooLong.kind to tooLong.reason)
        listOf(400, 422).forEach {
            val rejected = serverTextFailure(TextServerException.Failed("HTTP $it", it))
            assertEquals(CaptureFailureKind.FLAG to CaptureFailureReason.TEXT_REFUSED, rejected.kind to rejected.reason, "HTTP $it")
        }
        assertEquals(CaptureFailureKind.RETRY, serverTextFailure(TextServerException.Failed("HTTP 503", 503)).kind)
        val malformed = serverTextFailure(TextServerException.Failed("not a chat completion"))
        assertEquals(CaptureFailureKind.RETRY, malformed.kind)
        assertNull(malformed.reason)
    }
}
