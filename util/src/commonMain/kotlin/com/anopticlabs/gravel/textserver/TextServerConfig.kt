package com.anopticlabs.gravel.textserver

import com.anopticlabs.gravel.network.HostBoundToken
import com.anopticlabs.gravel.network.ServerTrustStore
import com.russhwolf.settings.Settings
import coredevices.util.security.SecretCipher
import kotlinx.serialization.Serializable

/** Gravel: where the Notebook's text model runs: on the phone, on the user's server, or one with the other as fallback. */
@Serializable
enum class TextServerMode { LocalOnly, ServerOnly, ServerFirst, LocalFirst }

/**
 * Gravel: the self-hosted text model server, an OpenAI-compatible chat completions endpoint. [serverUrl] is the full
 * https URL of the endpoint, used as entered (Ollama's is `/v1/chat/completions`); null means no server, and the
 * server modes then have nothing to run. [serverModel] is required by such servers. The bearer token and pinned
 * certificates live in [TextServerStore], never here.
 */
@Serializable
data class TextServerConfig(
    val mode: TextServerMode = TextServerMode.LocalOnly,
    val serverUrl: String? = null,
    val serverModel: String? = null,
    /** Off holds requests to the Wi-Fi-only rule the transcription server follows. */
    val serverOnMobileData: Boolean = false,
) {
    // The config is logged on every change into the log "Export logs" ships, and the URL names the user's own server.
    override fun toString(): String =
        "TextServerConfig(mode=$mode, serverUrl=${if (serverUrl != null) "[set]" else "null"}, serverModel=$serverModel, " +
            "serverOnMobileData=$serverOnMobileData)"
}

/**
 * Gravel: the text server's token, encrypted at rest and bound to the `host:port` it was saved for, and the
 * certificates pinned for it by `host:port`. Kept apart from the transcription server's, so removing one server
 * never drops a pin or token the other uses, even at the same address.
 */
class TextServerStore(private val settings: Settings, cipher: SecretCipher) : ServerTrustStore {
    private companion object {
        const val TOKEN_KEY = "text_server_token_bound"
        const val PIN_PREFIX = "text_server_pin_"
    }

    private val token = HostBoundToken(settings, cipher, TOKEN_KEY)

    override fun tokenFor(hostPort: String?): String? = token.tokenFor(hostPort)

    override fun setToken(hostPort: String?, value: String?) = token.set(hostPort, value)

    override fun storedToken(): String? = token.stored()

    override fun pinnedFingerprint(hostPort: String): String? = settings.getStringOrNull(PIN_PREFIX + hostPort)

    override fun trust(hostPort: String, fingerprint: String) = settings.putString(PIN_PREFIX + hostPort, fingerprint)

    override fun forget(hostPort: String) = settings.remove(PIN_PREFIX + hostPort)
}
