package com.anopticlabs.gravel.notebook.routers

import kotlinx.serialization.Serializable

/**
 * Signing certificates whose apps' routers Gravel trusts as shipped: the lowercase hex SHA-256 of
 * the certificate's DER bytes, as `apksigner verify --print-certs` prints it. An entry is added
 * with the app's name in a comment beside it, and only for an app whose router Gravel's own
 * documentation points users to. The user can remove any of them in the settings.
 */
val DEFAULT_TRUSTED_ROUTER_SIGNERS: Set<String> = setOf(
    // Gravel's release key, which also signs the router apps shipped with each Gravel release (routers/README.md).
    "5a7b2a794dcd6a75c77948283a1d014b4e80e62a2f8a684a10d3bbb57ea36106",
)

/** A certificate the user added, by its digest, with what they called it. A stored format (the trust file, an export). */
@Serializable
data class TrustedSigner(val sha256: String, val label: String? = null, val addedAtMillis: Long = 0L)

/**
 * The user's changes to the trust: certificates [added], and built-in ones removed. Kept apart
 * from the built-in list, so a certificate a later build adds reaches a phone that already has a
 * file, and a removal survives that. A stored format.
 */
@Serializable
data class TrustEntries(val added: List<TrustedSigner> = emptyList(), val removedDefaults: List<String> = emptyList()) {
    /** The same entries with malformed digests dropped, digests and labels normalized, a digest kept once (the last wins) and the caps applied. */
    fun cleaned(): TrustEntries {
        val added = added
            .mapNotNull { entry -> RouterTrust.normalizeDigest(entry.sha256)?.let { entry.copy(sha256 = it, label = RouterTrust.normalizeLabel(entry.label)) } }
            .associateBy { it.sha256 }.values.toList().takeLast(MAX_ENTRIES)
        val removed = removedDefaults.mapNotNull(RouterTrust::normalizeDigest).distinct().take(MAX_ENTRIES)
        return TrustEntries(added, removed)
    }

    companion object {
        val EMPTY = TrustEntries()

        const val MAX_ENTRIES = 64
        const val MAX_LABEL_CHARS = 64
    }
}

/** One certificate on the list in force, as the screen shows it; [builtIn] ones are from [DEFAULT_TRUSTED_ROUTER_SIGNERS]. */
data class TrustedCertificate(val sha256: String, val label: String?, val builtIn: Boolean, val addedAtMillis: Long?)

object RouterTrust {
    private val DIGEST = Regex("[0-9a-f]{64}")

    /** [value] as a stored digest, or null when it is not 64 hex digits. */
    fun normalizeDigest(value: String): String? = value.trim().lowercase().takeIf { DIGEST.matches(it) }

    /** [value] trimmed, without control characters and within the cap; null when nothing is left. */
    fun normalizeLabel(value: String?): String? = value?.filterNot { it.isISOControl() }?.trim()?.take(TrustEntries.MAX_LABEL_CHARS)?.ifEmpty { null }

    /** The built-in list minus what the user removed, then what the user added; an added entry for a built-in digest only names it. */
    fun certificates(entries: TrustEntries, defaults: Set<String> = DEFAULT_TRUSTED_ROUTER_SIGNERS): List<TrustedCertificate> {
        val removed = entries.removedDefaults.toSet()
        val added = entries.added.associateBy { it.sha256 }
        val builtIn = defaults.filterNot { it in removed }.map { TrustedCertificate(it, added[it]?.label, builtIn = true, added[it]?.addedAtMillis) }
        val own = entries.added.filterNot { it.sha256 in defaults }.map { TrustedCertificate(it.sha256, it.label, builtIn = false, it.addedAtMillis) }
        return builtIn + own
    }

    fun trustedDigests(entries: TrustEntries, defaults: Set<String> = DEFAULT_TRUSTED_ROUTER_SIGNERS): Set<String> =
        certificates(entries, defaults).mapTo(mutableSetOf()) { it.sha256 }

    /**
     * Whether an app signed by [signer] (its one current certificate) may receive routed text: the
     * certificate is in [trusted], or a trusted one is earlier in the app's signing lineage
     * ([signedBy], the platform's rotation-aware check).
     */
    fun isTrusted(signer: String, trusted: Set<String>, signedBy: (String) -> Boolean): Boolean =
        signer in trusted || trusted.any { it != signer && signedBy(it) }
}
