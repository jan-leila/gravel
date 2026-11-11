package com.anopticlabs.gravel.notebook.routers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.anopticlabs.gravel.notebook.backup.toHex
import java.security.MessageDigest

/**
 * A router another app declares, as installed now. [id] is what the receiver declares and
 * [namespace] the lowercase hex SHA-256 of the app's one signing certificate, so [ref] is the
* name the routing config forwards to; [label], [description] and [appName] are text another
 * app wrote; [needs] what it declared it wants beyond the text.
 */
data class InstalledRouter(
    val packageName: String,
    val receiverClass: String,
    val id: String,
    val namespace: String,
    val label: String,
    val description: String?,
    val appName: String,
    val needs: Set<Need> = emptySet(),
) {
    val ref: RouterRef get() = RouterRef(namespace, id)
}

/** The router receivers installed apps declare under the router API ([RouterApi]), read from the package manager. */
interface InstalledRouters {
    /** Every receiver filtering on the action, exported, guarded by the permission and declaring an id, in an app with one signer. */
    fun discover(): List<InstalledRouter>

    /** The receiver as it is now, or null when it is gone or no longer qualifies as a router. */
    fun resolve(packageName: String, receiverClass: String): InstalledRouter?

    /** Whether [sha256] signed [packageName], now or earlier in its signing lineage; false for a package that is gone. */
    fun signedBy(packageName: String, sha256: String): Boolean
}

class AndroidInstalledRouters(private val context: Context) : InstalledRouters {
    private val pm: PackageManager get() = context.packageManager

    override fun discover(): List<InstalledRouter> = query(Intent(RouterApi.ACTION_ROUTE_TEXT)).mapNotNull { it.toRouter() }

    override fun resolve(packageName: String, receiverClass: String): InstalledRouter? =
        query(Intent(RouterApi.ACTION_ROUTE_TEXT).setComponent(ComponentName(packageName, receiverClass)))
            .firstOrNull { it.activityInfo?.packageName == packageName && it.activityInfo?.name == receiverClass }
            ?.toRouter()

    override fun signedBy(packageName: String, sha256: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val digest = RouterTrust.normalizeDigest(sha256) ?: return false
        val bytes = digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return try {
            pm.hasSigningCertificate(packageName, bytes, PackageManager.CERT_INPUT_SHA256)
        } catch (e: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun query(intent: Intent): List<ResolveInfo> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

    // A receiver that is not exported, or not guarded by the API's permission, is not a router: the
    // system would skip it without a word, or any app could send to it in Gravel's name. An app
    // signed by several certificates has no single namespace, so its receivers are not routers either.
    private fun ResolveInfo.toRouter(): InstalledRouter? {
        val info = activityInfo ?: return null
        if (!info.exported || info.permission != RouterApi.PERMISSION_ROUTE_TEXT) return null
        val namespace = signers(info.packageName).singleOrNull() ?: return null
        val meta = info.metaData
        val id = meta?.getString(RouterApi.META_ID)?.takeIf { RoutingConfig.isRouterId(it) } ?: return null
        val label = meta.getString(RouterApi.META_LABEL)?.clean(RouterApi.MAX_LABEL_CHARS)
            ?: loadLabel(pm).toString().clean(RouterApi.MAX_LABEL_CHARS)
            ?: info.packageName
        val description = meta.getString(RouterApi.META_DESCRIPTION)?.clean(RouterApi.MAX_DESCRIPTION_CHARS)
        val appName = info.applicationInfo?.loadLabel(pm)?.toString()?.clean(RouterApi.MAX_LABEL_CHARS) ?: info.packageName
        return InstalledRouter(info.packageName, info.name, id, namespace, label, description, appName, RouterApi.parseNeeds(meta.getString(RouterApi.META_NEEDS)))
    }

    private fun String.clean(max: Int): String? = filterNot { it.isISOControl() }.trim().take(max).ifEmpty { null }

    @Suppress("DEPRECATION")
    private fun signers(packageName: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.signingInfo?.apkContentsSigners.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }
    }
}
