package com.anopticlabs.gravel.router.common

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/** One request to make: the builders of each integration produce it, the tests read it, [Http] sends it. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
)

/** What the server answered: the status and up to a few kilobytes of its body, for a reason to show. */
data class HttpAnswer(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299

    /**
     * The reason a retry would not change, for an answer every integration reads the same way: a
     * redirect (the request is not followed, so the address is one the API is not at, http:// for
     * an https:// server for example) or no such API at the address. Null for any other status.
     */
    fun addressReason(service: String): String? = when (status) {
        in 300..399 -> "$service redirected the request (HTTP $status); check the address"
        404, 405, 410 -> "$service has no such API at that address (HTTP $status)"
        else -> null
    }
}

/**
 * Sends requests with the platform's HTTP client. Cleartext goes only to a private address, and
 * only when the app allows it ([allowPrivateCleartext]: the self-hosted relays often have no TLS
 * on a LAN); anything else must be https.
 */
class Http(private val allowPrivateCleartext: Boolean = false) {
    /** False for a request [send] would refuse; the builders check it so a bad address is a refusal, not a retry. */
    fun allows(url: String): Boolean = allowed(url, allowPrivateCleartext)

    @Throws(IOException::class)
    fun send(request: HttpRequest): HttpAnswer {
        if (!allows(request.url)) throw IOException("address not allowed")
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readUpTo(it, MAX_BODY_BYTES) }?.decodeToString().orEmpty()
            return HttpAnswer(status, body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // A loop, as the one-call readNBytes is an API 33 method the older platforms the apps run on do not have.
        private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
            val out = ByteArray(limit)
            var filled = 0
            while (filled < limit) {
                val read = stream.read(out, filled, limit - filled)
                if (read < 0) break
                filled += read
            }
            return out.copyOf(filled)
        }

        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val MAX_BODY_BYTES = 4 * 1024

        /** The rule on its own, for tests: https anywhere; http only to a private host, and only when allowed. */
        fun allowed(url: String, allowPrivateCleartext: Boolean): Boolean {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return false
            }
            val host = uri.host ?: return false
            return when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> allowPrivateCleartext && isPrivateHost(host)
                else -> false
            }
        }

        /**
         * A LAN name (under a LAN suffix, or with no dot at all, which no public DNS answers for) or a
         * private, shared (100.64/10: carrier NAT and Tailscale), link-local or loopback address,
         * where a relay without TLS may live.
         */
        fun isPrivateHost(host: String): Boolean {
            val name = host.lowercase().trimEnd('.')
            if (name.isEmpty()) return false
            if (name == "localhost" || name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".home.arpa") || name.endsWith(".internal")) return true
            if (!name.contains('.') && !name.contains(':')) return true
            val v4 = name.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() }
            if (v4 != null && v4.all { it != null && it in 0..255 }) {
                val (a, b) = v4.map { it!! }
                return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
            }
            val v6 = name.removePrefix("[").removeSuffix("]")
            if (v6.contains(':')) {
                val head = v6.substringBefore(':').lowercase()
                return v6 == "::1" || head.startsWith("fc") || head.startsWith("fd") || head.startsWith("fe8") || head.startsWith("fe9") || head.startsWith("fea") || head.startsWith("feb")
            }
            return false
        }
    }
}

/** The JSON text of a string, quoted and escaped as RFC 8259 asks; the builders put requests together with it. */
fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
    }
    return out.append('"').toString()
}
