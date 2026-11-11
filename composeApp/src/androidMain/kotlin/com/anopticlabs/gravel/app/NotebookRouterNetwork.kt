package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.routers.PostResult
import com.anopticlabs.gravel.notebook.routers.TextPoster
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Gravel: the Notebook's Network router posts through this. The response is answered by its
 * status alone; its body is never read, so a server's reply cannot cost memory.
 */
class KtorTextPoster(private val client: HttpClient) : TextPoster {
    override suspend fun post(url: String, bearerToken: String?, bodyJson: String): PostResult = try {
        client.prepareRequest(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(bodyJson)
        }.execute { response -> PostResult.Answered(response.status.value) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (neverConnected(e)) PostResult.NotConnected else PostResult.Failed(e::class.simpleName ?: "failure")
    }

    private fun neverConnected(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is ConnectTimeoutException
    }
}

/** The client router posts go out on: the text is small, and a slow server is tried again later rather than waited on. */
fun routerPostClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
        }
    }
}
