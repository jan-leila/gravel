package com.anopticlabs.gravel.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Call from an `execute` block: runs [read] on this response's body and cancels the body if [read] leaves it
 * unfinished, whether it returns early, throws or is canceled.
 */
suspend fun <T> HttpResponse.readBody(read: suspend (ByteReadChannel) -> T): T {
    val channel = bodyAsChannel()
    try {
        return read(channel)
    } finally {
        // The channel is fed by a copier under the client's job that sleeps on a full, unread buffer until this
        // channel is read or closed; execute's cancel of the raw body does not reach it, and execute's normal return
        // waits for it (ktor 3.5.1: DefaultTransform.kt defaultTransformers, ByteChannel.kt flush,
        // ByteChannelUtils.kt attachWriterJob, HttpStatement.kt cleanup).
        if (!channel.isClosedForRead) channel.cancel()
    }
}

/**
 * Call from an `execute` block: the body when it is at most [maxBytes], else null. A declared length past the
 * bound is refused unread, and a body that runs past it is refused there with the rest left on the wire, so a
 * reply from whatever answers at a user-entered URL is never buffered whole.
 */
suspend fun HttpResponse.readBoundedBody(maxBytes: Int): ByteArray? {
    val declared = contentLength()
    if (declared != null && declared > maxBytes) return null
    val bytes = readBody { it.readRemaining((maxBytes + 1).toLong()).readByteArray() }
    return bytes.takeIf { it.size <= maxBytes }
}
