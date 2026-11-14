package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NetworkRouterDeliveryTest {

    private class Post(val url: String, val token: String?, val body: String)

    private class FakePoster(var result: PostResult = PostResult.Answered(200)) : TextPoster {
        val posts = mutableListOf<Post>()
        override suspend fun post(url: String, bearerToken: String?, bodyJson: String): PostResult {
            posts += Post(url, bearerToken, bodyJson)
            return result
        }
    }

    private val poster = FakePoster()
    private var online = true
    private val delivery = NetworkRouterDelivery(poster) { online }
    private val hook = RouterConfig.Network("hook", "Webhook", "https://example.test/notes", bearerToken = "s3cret")
    private val here = RoutedLocation(52.52, 13.405, 30f)
    private val text = RoutedText("c1", "2026-09-22T17:00:00+02:00", "buy milk", here)

    private fun failed(block: suspend () -> Unit): CaptureProcessingException = assertFailsWith { runBlocking { block() } }

    @Test
    fun aTwoHundredTakesThePayloadPostedUnderTheApisNamesWithTheToken() = runBlocking<Unit> {
        for (status in listOf(200, 201, 204)) {
            poster.result = PostResult.Answered(status)
            delivery.deliver(hook, text, setOf(Need.RECORDED_AT))
        }
        val post = poster.posts.last()
        assertEquals("https://example.test/notes", post.url)
        assertEquals("s3cret", post.token)
        val body = NotebookJson.parseToJsonElement(post.body).jsonObject
        assertEquals(setOf("api_version", "capture_id", "recorded_at", "text", "unmatched_text"), body.keys)
        assertEquals(RouterApi.VERSION, body.getValue("api_version").jsonPrimitive.content.toInt())
        assertEquals("c1", body.getValue("capture_id").jsonPrimitive.content)
        assertEquals("2026-09-22T17:00:00+02:00", body.getValue("recorded_at").jsonPrimitive.content)
        assertEquals("buy milk", body.getValue("text").jsonPrimitive.content)
        poster.result = PostResult.Answered(200)
        delivery.deliver(hook.copy(bearerToken = null), text, emptySet())
        assertNull(poster.posts.last().token)
    }

    /** Only the shared parts leave the phone: the body names what it carries and nothing else. */
    @Test
    fun theBodyCarriesOnlyTheSharedParts() = runBlocking<Unit> {
        suspend fun body(share: Set<Need>, text: RoutedText = this@NetworkRouterDeliveryTest.text) =
            delivery.deliver(hook, text, share).let { NotebookJson.parseToJsonElement(poster.posts.last().body).jsonObject }
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text"), body(emptySet()).keys)
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text", "recorded_at"), body(setOf(Need.RECORDED_AT)).keys)
        val placed = body(setOf(Need.LOCATION))
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text", "location"), placed.keys)
        val location = placed.getValue("location").jsonObject
        assertEquals(setOf("latitude", "longitude", "accuracy_m"), location.keys)
        assertEquals("52.52", location.getValue("latitude").jsonPrimitive.content)
        assertEquals("13.405", location.getValue("longitude").jsonPrimitive.content)
        assertEquals("30.0", location.getValue("accuracy_m").jsonPrimitive.content)
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text", "recorded_at", "location"), body(setOf(Need.LOCATION, Need.RECORDED_AT)).keys)
        assertEquals(setOf("latitude", "longitude"), body(setOf(Need.LOCATION), text.copy(location = RoutedLocation(1.0, 2.0))).getValue("location").jsonObject.keys)
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text"), body(setOf(Need.LOCATION), text.copy(location = null)).keys, "a granted location the recording has not is simply absent")
    }

    @Test
    fun theBodyCarriesTheCutTextAndTheMappedFieldsButNotTheMatches() = runBlocking<Unit> {
        val routed = RoutedText(
            "c1", "2026-09-22T17:00:00+02:00", "add a task: buy milk",
            matches = listOf(TextMatch("tasks", 0, 10, cut = true, groups = listOf(MatchGroup("item", 12, 20)))),
        )
        delivery.deliver(hook.copy(fields = listOf(FieldMapping("title", "item"), FieldMapping("said", FieldMapping.WHOLE_TEXT))), routed, emptySet())
        val body = NotebookJson.parseToJsonElement(poster.posts.last().body).jsonObject
        assertEquals(setOf("api_version", "capture_id", "text", "unmatched_text", "fields"), body.keys)
        assertEquals("buy milk", body.getValue("unmatched_text").jsonPrimitive.content)
        val fields = body.getValue("fields").jsonObject
        assertEquals(mapOf("title" to "buy milk", "said" to "add a task: buy milk"), fields.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun aClientErrorFlagsAndAServerErrorOrAThrottleRetries() {
        for (status in listOf(400, 401, 403, 404, 422)) {
            poster.result = PostResult.Answered(status)
            val e = failed { delivery.deliver(hook, text, emptySet()) }
            assertEquals(CaptureFailureKind.FLAG, e.kind, "HTTP $status")
            assertEquals(CaptureFailureReason.ROUTER_UNAVAILABLE, e.reason)
        }
        for (status in listOf(408, 429, 500, 502, 503, 301)) {
            poster.result = PostResult.Answered(status)
            assertEquals(CaptureFailureKind.RETRY, failed { delivery.deliver(hook, text, emptySet()) }.kind, "HTTP $status")
        }
        poster.result = PostResult.Failed("SocketTimeoutException")
        val lost = failed { delivery.deliver(hook, text, emptySet()) }
        assertEquals(CaptureFailureKind.RETRY, lost.kind)
        assertTrue(lost.message!!.contains("SocketTimeoutException"))
    }

    @Test
    fun noNetworkOrAServerOutOfReachWaitsForTheNetwork() {
        online = false
        val offline = failed { delivery.deliver(hook, text, emptySet()) }
        assertEquals(CaptureFailureKind.WAIT, offline.kind)
        assertTrue(offline.waitsForNetwork)
        assertTrue(poster.posts.isEmpty(), "nothing is posted without a network")
        online = true
        poster.result = PostResult.NotConnected
        val unreachable = failed { delivery.deliver(hook, text, emptySet()) }
        assertEquals(CaptureFailureKind.WAIT, unreachable.kind)
        assertTrue(unreachable.waitsForNetwork)
    }

    @Test
    fun anAddressThatIsNotHttpsIsFlaggedWithoutAPost() {
        val e = failed { delivery.deliver(hook.copy(url = "http://example.test/notes"), text, emptySet()) }
        assertEquals(CaptureFailureKind.FLAG, e.kind)
        assertTrue(poster.posts.isEmpty())
        assertFailsWith<IllegalArgumentException> { runBlocking { delivery.deliver(RouterConfig.Note("n", "Notes"), text, emptySet()) } }
    }
}
