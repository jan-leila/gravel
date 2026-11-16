package com.anopticlabs.gravel.network

import com.russhwolf.settings.Settings
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher

/**
 * Gravel: what the server settings dialog and a request read and write for one self-hosted server: the bearer
 * token, bound to the `host:port` it was saved for, and the certificate fingerprints pinned by `host:port`.
 */
interface ServerTrustStore {
    /** The token saved for [hostPort]; null for any other address. */
    fun tokenFor(hostPort: String?): String?

    /** Saves [value] as the token of [hostPort], or removes the saved token without either. */
    fun setToken(hostPort: String?, value: String?)

    /** The saved token with its address as stored (encrypted); null when none is saved. */
    fun storedToken(): String?

    fun pinnedFingerprint(hostPort: String): String?

    fun trust(hostPort: String, fingerprint: String)

    fun forget(hostPort: String)
}

/**
 * Gravel: a token kept encrypted under [key] as the address, a line break and the token in one value, so one read
 * gives a token and whose it is, and a URL edited to another server never carries it there.
 */
class HostBoundToken(private val settings: Settings, cipher: SecretCipher, private val key: String) {
    private val setting = EncryptedStringSetting(settings, cipher, key)

    fun tokenFor(hostPort: String?): String? {
        val saved = setting.get() ?: return null
        val at = saved.indexOf('\n')
        return if (hostPort != null && at > 0 && saved.substring(0, at) == hostPort) saved.substring(at + 1) else null
    }

    /** A blank [token] or no [hostPort] removes the saved one. */
    fun set(hostPort: String?, token: String?) {
        val value = token?.trim()?.takeIf { it.isNotBlank() }
        setting.set(if (hostPort != null && value != null) "$hostPort\n$value" else null)
    }

    fun stored(): String? = settings.getStringOrNull(key)
}
