package com.anopticlabs.gravel.textserver

import com.anopticlabs.gravel.network.NotOnAllowedNetworkException
import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import coredevices.util.transcription.FakeUnmeteredWifi
import coredevices.util.transcription.TransportFailure
import coredevices.util.transcription.serverHostPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The text server client against a mock engine: what is configured enough to send, the request as the server sees
 * it, the Wi-Fi-only rule, each failure's mapping with the server's address kept out of it, and the reply bound.
 */
class SelfHostedTextServiceTest {

    private object PlainCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(stored: String): DecryptResult =
            if (stored.startsWith("enc:")) DecryptResult.Success(stored.removePrefix("enc:").reversed()) else DecryptResult.Unrecoverable
    }

    private class Harness(
        url: String? = "https://inference.example.net/v1/chat/completions",
        model: String? = "qwen2.5:7b-instruct",
        token: String? = null,
        onMobileData: Boolean = false,
        val wifi: FakeUnmeteredWifi = FakeUnmeteredWifi(),
        requestLimit: Duration = SelfHostedTextService.REQUEST_LIMIT,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = ArrayList<HttpRequestData>()
        val bodies = ArrayList<String>()
        val wifiOnlyClients = ArrayList<Boolean>()
        val answerWaits = ArrayList<Duration>()
        val store = TextServerStore(MapSettings(), PlainCipher).also { it.setToken(url?.let(::serverHostPort), token) }
        val config = MutableStateFlow(
            CoreConfig(textServerConfig = TextServerConfig(TextServerMode.ServerOnly, url, model, onMobileData)),
        )
        val service = SelfHostedTextService(
            coreConfigFlow = CoreConfigFlow(config),
            store = store,
            unmeteredWifi = wifi,
            clientFactory = { _, _, wifiOnly, answerWait ->
                wifiOnlyClients += wifiOnly
                answerWaits += answerWait
                HttpClient(MockEngine { request ->
                    requests += request
                    bodies += request.body.toByteArray().decodeToString()
                    handler(request)
                })
            },
            requestLimit = requestLimit,
        )

        suspend fun generate() = service.generate("Title the note.", "buy milk", 512)
    }

    private fun chatReply(content: String) = """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}"""

    private fun MockRequestHandleScope.ok(content: String) =
        respond(chatReply(content), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun aServerNeedsAValidUrlAndAModel() = runBlocking<Unit> {
        for ((url, model) in listOf(
            null to "m",
            "http://inference.example.net/v1/chat/completions" to "m",
            "https://user:pw@inference.example.net/v1/chat/completions" to "m",
            "https://inference.example.net/v1/chat/completions" to null,
            "https://inference.example.net/v1/chat/completions" to "  ",
        )) {
            val h = Harness(url = url, model = model) { ok("never") }
            assertFalse(h.service.configured(), "$url $model")
            assertFailsWith<TextServerException.NotConfigured> { h.generate() }
            assertTrue(h.requests.isEmpty())
        }
        assertTrue(Harness { ok("x") }.service.configured())
    }

    @Test
    fun theRequestGoesToTheUrlAsEnteredWithTheTokenAndTheChatBody() = runBlocking {
        val h = Harness(token = "tok-1") { ok("{\"title\":\"Milk\"}") }
        assertEquals("{\"title\":\"Milk\"}", h.generate())
        val request = h.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://inference.example.net/v1/chat/completions", request.url.toString())
        assertEquals("Bearer tok-1", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("application/json"))
        val body = Json.parseToJsonElement(h.bodies.single()).jsonObject
        assertEquals("qwen2.5:7b-instruct", body["model"]!!.jsonPrimitive.content)
        assertEquals("buy milk", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(listOf(SelfHostedTextService.ANSWER_WAIT), h.answerWaits)
    }

    @Test
    fun withoutATokenNoAuthorizationIsSent() = runBlocking {
        val h = Harness { ok("x") }
        h.generate()
        assertNull(h.requests.single().headers[HttpHeaders.Authorization])
    }

    /** A token saved for another address is never sent to this one. */
    @Test
    fun aTokenSavedForAnotherServerStaysThere() = runBlocking {
        val h = Harness { ok("x") }
        h.store.setToken("other.example.net:443", "tok-other")
        h.generate()
        assertNull(h.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun theWifiOnlyRuleHoldsTheRequestUnlessMobileDataIsAllowed() = runBlocking<Unit> {
        val off = Harness(wifi = FakeUnmeteredWifi(usable = false)) { ok("x") }
        assertFalse(off.service.allowedOnCurrentNetwork())
        assertFailsWith<TextServerException.NotAllowedOnNetwork> { off.generate() }
        assertTrue(off.requests.isEmpty())

        val mobile = Harness(onMobileData = true, wifi = FakeUnmeteredWifi(usable = false)) { ok("x") }
        assertEquals("x", mobile.generate())
        assertEquals(listOf(false), mobile.wifiOnlyClients)

        val wifi = Harness { ok("x") }
        wifi.generate()
        assertEquals(listOf(true), wifi.wifiOnlyClients, "a request on Wi-Fi goes through the client held to the rule")
    }

    @Test
    fun aSocketTheRuleRefusesOrANetworkLostToItIsTheRulesFailure() = runBlocking<Unit> {
        val refused = Harness { throw IOException("connect failed", NotOnAllowedNetworkException()) }
        assertFailsWith<TextServerException.NotAllowedOnNetwork> { refused.generate() }

        val wifi = FakeUnmeteredWifi()
        val left = Harness(wifi = wifi) { wifi.usable.value = false; throw IOException("connection reset") }
        assertFailsWith<TextServerException.NotAllowedOnNetwork> { left.generate() }
    }

    @Test
    fun transportFailuresCarryTheirKindAndNotTheServersAddress() = runBlocking {
        val h = Harness { throw java.net.ConnectException("Failed to connect to inference.example.net/100.64.0.5:443") }
        val error = assertFailsWith<TextServerException.Unreachable> { h.generate() }
        assertEquals(TransportFailure.NOT_CONNECTED, error.failure)
        assertEquals("connection refused", error.message)
        val printed = error.stackTraceToString()
        assertFalse(printed.contains("example.net"), printed)
        assertFalse(printed.contains("100.64.0.5"), printed)
    }

    @Test
    fun anHttpErrorIsAFailureWithItsStatus() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            val h = Harness { respond("""{"error":"nope"}""", status) }
            assertEquals(status.value, assertFailsWith<TextServerException.Failed> { h.generate() }.status)
        }
    }

    @Test
    fun aReplyThatIsNotAChatCompletionIsAFailure() = runBlocking {
        val h = Harness { respond("""{"text":"hello"}""", HttpStatusCode.OK) }
        assertNull(assertFailsWith<TextServerException.Failed> { h.generate() }.status)
    }

    @Test
    fun theReasoningBlockNeverReachesTheNotebook() = runBlocking {
        val h = Harness { ok("<think>milk, so shopping</think>{\"title\":\"Milk\"}") }
        assertEquals("{\"title\":\"Milk\"}", h.generate())
    }

    @Test
    fun anOversizedReplyIsRefused() = runBlocking {
        val declared = Harness { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "10000000")) }
        assertTrue(assertFailsWith<TextServerException.Failed> { declared.generate() }.message!!.contains("too large"))

        val undeclared = Harness { ok("a".repeat(SelfHostedTextService.MAX_REPLY_BYTES)) }
        assertTrue(assertFailsWith<TextServerException.Failed> { undeclared.generate() }.message!!.contains("too large"))

        val padding = " ".repeat(SelfHostedTextService.MAX_REPLY_BYTES - chatReply("ok").length)
        assertEquals("ok", Harness { respond(chatReply("ok") + padding, HttpStatusCode.OK) }.generate(), "at the bound the reply is read")
    }

    @Test
    fun aRequestStillOpenAtTheLimitIsAFailure() = runBlocking {
        val h = Harness(requestLimit = 200.milliseconds) { awaitCancellation() }
        assertTrue(assertFailsWith<TextServerException.Failed> { h.generate() }.message!!.contains("did not answer"))
    }

    /** The test runs on the dialog's unsaved values, off the Wi-Fi-only rule, on a client of its own. */
    @Test
    fun theConnectionTestSendsATinyPromptWithTheDialogsValues() = runBlocking {
        val h = Harness(url = null, model = null, wifi = FakeUnmeteredWifi(usable = false)) { ok("OK") }
        val result = h.service.testConnection("https://new.example.net/v1/chat/completions", "llama3.1:8b", "typed")
        assertEquals(TextServerTestResult(200, chatReply = true), result)
        val request = h.requests.single()
        assertEquals("Bearer typed", request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(h.bodies.single()).jsonObject
        assertEquals("llama3.1:8b", body["model"]!!.jsonPrimitive.content)
        assertEquals(SelfHostedTextService.TEST_TOKENS.toString(), body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(listOf(false), h.wifiOnlyClients)
        assertEquals(listOf(SelfHostedTextService.TEST_ANSWER_WAIT), h.answerWaits)

        val notChat = Harness { respond("<html>hello</html>", HttpStatusCode.OK) }
        assertEquals(TextServerTestResult(200, chatReply = false), notChat.service.testConnection("https://inference.example.net/", "m", null))
        val missing = Harness { respond("{}", HttpStatusCode.NotFound) }
        assertEquals(TextServerTestResult(404, chatReply = false), missing.service.testConnection("https://inference.example.net/v1/chat/completions", "m", null))
    }

    /** One server's token and pins never answer for the other's, even at the same address. */
    @Test
    fun theTextServersTokenAndPinsAreItsOwn() {
        val settings = MapSettings()
        val text = TextServerStore(settings, PlainCipher)
        val speech = coredevices.util.transcription.SelfHostedServerStore(settings, PlainCipher)
        val hp = "home.example.net:443"
        speech.setToken(hp, "stt")
        speech.trust(hp, "AA:BB")
        assertNull(text.tokenFor(hp))
        assertNull(text.pinnedFingerprint(hp))
        text.setToken(hp, " llm ")
        text.trust(hp, "CC:DD")
        assertEquals("llm", text.tokenFor(hp))
        assertEquals("stt", speech.tokenFor(hp))
        text.forget(hp)
        assertEquals("AA:BB", speech.pinnedFingerprint(hp))
        assertNull(text.tokenFor("other.example.net:443"))
        text.setToken(hp, "  ")
        assertNull(text.storedToken(), "a blank token removes the saved one")
        assertFalse(settings.keys.any { it.contains("llm") || settings.getStringOrNull(it)?.contains("llm") == true }, "stored encrypted")
    }
}
