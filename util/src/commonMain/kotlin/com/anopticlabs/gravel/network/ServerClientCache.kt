package com.anopticlabs.gravel.network

import coredevices.util.transcription.SelfHostedClientFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Gravel: one self-hosted server client, built through [factory] for a `host:port` and Wi-Fi-only choice and
 * rebuilt when either changes. A replaced client is closed, so a caller that can have two requests out at once
 * keeps one cache per request kind.
 */
class ServerClientCache(
    private val factory: SelfHostedClientFactory,
    private val answerWait: Duration,
    private val pinnedFingerprint: (hostPort: String) -> String?,
) {
    private val mutex = Mutex()
    private var client: Pair<Pair<String, Boolean>, HttpClient>? = null

    suspend fun clientFor(hostPort: String, wifiOnly: Boolean): HttpClient {
        val key = hostPort to wifiOnly
        return mutex.withLock {
            client?.takeIf { it.first == key }?.second ?: run {
                client?.second?.close()
                factory.create(hostPort, { pinnedFingerprint(hostPort) }, wifiOnly, answerWait).also { client = key to it }
            }
        }
    }
}
