package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.routers.PostResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class KtorTextPosterTest {

    @Test
    fun postsTheBodyAsJsonWithTheBearerTokenAndAnswersWithTheStatus() = runBlocking<Unit> {
        var seen: Triple<String, String?, String>? = null
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(';'))
            seen = Triple(request.url.toString(), request.headers["Authorization"], request.body.toByteArray().decodeToString())
            respond("ignored body", HttpStatusCode.Created)
        }
        val poster = KtorTextPoster(HttpClient(engine))
        assertEquals(PostResult.Answered(201), poster.post("https://example.test/notes", "s3cret", """{"text":"hi"}"""))
        assertEquals(Triple("https://example.test/notes", "Bearer s3cret", """{"text":"hi"}"""), seen)
        assertEquals(PostResult.Answered(201), poster.post("https://example.test/notes", null, "{}"))
        assertEquals(null, seen!!.second)
    }

    @Test
    fun aServerOutOfReachIsNotConnectedAndAnyOtherFailureIsNamed() = runBlocking<Unit> {
        for (failure in listOf(UnknownHostException("example.test"), ConnectException("refused"))) {
            val poster = KtorTextPoster(HttpClient(MockEngine { throw failure }))
            assertEquals(PostResult.NotConnected, poster.post("https://example.test/notes", null, "{}"))
        }
        val poster = KtorTextPoster(HttpClient(MockEngine { throw SocketTimeoutException("read") }))
        assertEquals(PostResult.Failed("SocketTimeoutException"), poster.post("https://example.test/notes", null, "{}"))
    }
}
