package com.anopticlabs.gravel.network

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * GETs [url] and returns its body, read until it ends or is longer than [maxBytes] (the result is then longer than
 * [maxBytes], so a caller comparing the length with an expected size refuses it); null when the answer is not a
 * success.
 */
suspend fun HttpClient.downloadAtMost(url: String, maxBytes: Long): ByteArray? =
    prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) return@execute null
        response.readBody { channel ->
            val body = Buffer()
            val chunk = ByteArray(16 * 1024)
            while (body.size <= maxBytes) {
                val read = channel.readAvailable(chunk, 0, chunk.size)
                if (read == -1) break
                body.write(chunk, 0, read)
            }
            body.readByteArray()
        }
    }
