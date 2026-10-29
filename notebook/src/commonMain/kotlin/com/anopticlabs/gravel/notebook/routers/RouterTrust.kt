package com.anopticlabs.gravel.notebook.routers

import kotlinx.serialization.Serializable

/**
 * Signing certificates whose apps' routers Gravel trusts as shipped: the lowercase hex SHA-256 of
 * the certificate's DER bytes, as `apksigner verify --print-certs` prints it. An entry is added
 * with the app's name in a comment beside it, and only for an app whose router Gravel's own
 * documentation points users to.
 */
val DEFAULT_TRUSTED_ROUTER_SIGNERS: Set<String> = emptySet()

/** An app the user marked as trusted, by the signer it had then; a stored format in the trust file. */
@Serializable
data class TrustedRouterApp(val packageName: String, val sha256: String, val addedAtMillis: Long)

object RouterTrust {
    private val DIGEST = Regex("[0-9a-f]{64}")

    /** [value] as a stored digest, or null when it is not 64 hex digits. */
    fun normalizeDigest(value: String): String? = value.trim().lowercase().takeIf { DIGEST.matches(it) }

    /**
     * Whether an app signed by [signers] (its current signing certificates) may receive routed
     * text: every signer is in the default list or among the user's entries for [packageName].
     * A user's entry trusts that app, not every app from its signer. An app with one signer is
     * also trusted when a trusted certificate is earlier in its signing lineage ([signedBy], the
     * platform's rotation-aware check); an app with several signers does not rotate.
     */
    fun isTrusted(
        packageName: String,
        signers: List<String>,
        entries: List<TrustedRouterApp>,
        signedBy: (String) -> Boolean,
        defaults: Set<String> = DEFAULT_TRUSTED_ROUTER_SIGNERS,
    ): Boolean {
        if (signers.isEmpty()) return false
        val own = entries.filter { it.packageName == packageName }.map { it.sha256 }.toSet()
        if (signers.all { it in defaults || it in own }) return true
        return signers.size == 1 && (defaults + own).any { signedBy(it) }
    }
}
