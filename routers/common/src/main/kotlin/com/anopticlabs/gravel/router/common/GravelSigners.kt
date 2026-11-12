package com.anopticlabs.gravel.router.common

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.security.MessageDigest

/**
 * Whether the permission guarding a router's receiver is Gravel's own. The system checks a
 * caller's permission by name alone, so while Gravel is absent any app could define a permission
 * of that name, hold it, and deliver in Gravel's name: the receiver therefore asks who defined
 * it. Gravel's definition is signature-level, in one of [GRAVEL_PACKAGES], in an app signed
 * by a key Gravel ships under ([TRUSTED]) or by this app's own key (a locally built Gravel beside
 * a locally built router, both on the one key).
 */
object GravelSigners {
    /** Gravel's two listings: F-Droid's, and the one signed with Gravel's own key, which the router apps share. */
    val GRAVEL_PACKAGES: Set<String> = setOf("com.anopticlabs.gravel", "com.anopticlabs.gravel.signed")

    /** The lowercase hex SHA-256 of each certificate Gravel is distributed under: F-Droid's key for it. */
    val TRUSTED: Set<String> = setOf(
        "ffb5bd16b212b0223051df349b36502f481756d45922a8e0ef00cd3ce108d5d2",
    )

    /** True when [RouterContract.PERMISSION_ROUTE_TEXT] is defined as Gravel defines it, by a Gravel this app trusts. */
    fun definesPermission(pm: PackageManager, selfPackage: String): Boolean {
        val info = try {
            pm.getPermissionInfo(RouterContract.PERMISSION_ROUTE_TEXT, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        @Suppress("DEPRECATION")
        val protection = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        return accepts(info.packageName, protection, signers(pm, info.packageName), signers(pm, selfPackage))
    }

    /** The rule on its own, for tests: the definer, its protection level, the definer's signers and this app's. */
    fun accepts(definer: String?, protection: Int, definerSigners: Set<String>?, selfSigners: Set<String>?): Boolean {
        if (definer !in GRAVEL_PACKAGES || protection != PermissionInfo.PROTECTION_SIGNATURE) return false
        val signer = definerSigners?.singleOrNull() ?: return false
        return signer in TRUSTED || signer == selfSigners?.singleOrNull()
    }

    /**
     * The SHA-256 digests of the certificates [packageName] is signed by now, lowercase hex; null
     * when the package is not installed. Through a key rotation the current certificate is what
     * counts, so a package rotated to an unknown key is not accepted on its history.
     */
    private fun signers(pm: PackageManager, packageName: String): Set<String>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners.map { digest(it.toByteArray()) }.toSet()
            else signing.signingCertificateHistory.lastOrNull()?.let { setOf(digest(it.toByteArray())) } ?: emptySet()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().map { digest(it.toByteArray()) }.toSet()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun digest(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }
}
