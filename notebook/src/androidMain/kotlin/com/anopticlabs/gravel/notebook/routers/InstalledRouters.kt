package com.anopticlabs.gravel.notebook.routers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.anopticlabs.gravel.notebook.backup.toHex
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A router another app declares, as installed now. [id] is what the receiver declares, or what
 * its app's router list names when [listed], and [namespace] the lowercase hex SHA-256 of the
 * app's one signing certificate, so [ref] is the name the routing config forwards to; [label],
 * [description] and [appName] are text another app wrote; [needs] what it declared it wants
 * beyond the text.
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
    val listed: Boolean = false,
) {
    val ref: RouterRef get() = RouterRef(namespace, id)
}

/** The routers installed apps declare under the router API ([RouterApi]), read from the package manager and the apps' router lists. */
interface InstalledRouters {
    /** Every router of every receiver filtering on the action, exported, guarded by the permission, in an app with one signer. */
    fun discover(): List<InstalledRouter>

    /** The router [id] of the receiver as it is now, or null when it is gone or no longer qualifies as a router. */
    fun resolve(packageName: String, receiverClass: String, id: String): InstalledRouter?

    /** Whether [sha256] signed [packageName], now or earlier in its signing lineage; false for a package that is gone. */
    fun signedBy(packageName: String, sha256: String): Boolean
}

class AndroidInstalledRouters(private val context: Context) : InstalledRouters {
    private val pm: PackageManager get() = context.packageManager

    override fun discover(): List<InstalledRouter> = query(Intent(RouterApi.ACTION_ROUTE_TEXT)).flatMap { it.toRouters() }

    override fun resolve(packageName: String, receiverClass: String, id: String): InstalledRouter? =
        query(Intent(RouterApi.ACTION_ROUTE_TEXT).setComponent(ComponentName(packageName, receiverClass)))
            .firstOrNull { it.activityInfo?.packageName == packageName && it.activityInfo?.name == receiverClass }
            ?.toRouters()
            ?.firstOrNull { it.id == id }

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
    private fun ResolveInfo.toRouters(): List<InstalledRouter> {
        val info = activityInfo ?: return emptyList()
        if (!info.exported || info.permission != RouterApi.PERMISSION_ROUTE_TEXT) return emptyList()
        val namespace = signers(info.packageName).singleOrNull() ?: return emptyList()
        val meta = info.metaData
        val appName = info.applicationInfo?.loadLabel(pm)?.toString()?.let { ListedRows.clean(it, RouterApi.MAX_LABEL_CHARS) } ?: info.packageName
        val declared = meta?.getString(RouterApi.META_ID)?.takeIf { RoutingConfig.isRouterId(it) }?.let { id ->
            val label = ListedRows.clean(meta.getString(RouterApi.META_LABEL), RouterApi.MAX_LABEL_CHARS)
                ?: ListedRows.clean(loadLabel(pm).toString(), RouterApi.MAX_LABEL_CHARS)
                ?: info.packageName
            val description = ListedRows.clean(meta.getString(RouterApi.META_DESCRIPTION), RouterApi.MAX_DESCRIPTION_CHARS)
            InstalledRouter(info.packageName, info.name, id, namespace, label, description, appName, RouterApi.parseNeeds(meta.getString(RouterApi.META_NEEDS)))
        }
        val listed = meta?.getString(RouterApi.META_LIST)?.let { authority ->
            ListedRows.read(listRows(info, authority), declared?.let { setOf(it.id) }.orEmpty()).map {
                InstalledRouter(info.packageName, info.name, it.id, namespace, it.label, it.description, appName, it.needs, listed = true)
            }
        }.orEmpty()
        return listOfNotNull(declared) + listed
    }

    /**
     * The rows of the receiver's router list. The provider has to belong to the receiver's own
     * package and be guarded like the receiver, or another app could put routers under this
     * one's name; it runs in that app, so the query is bounded by [RouterApi.LIST_WAIT_MILLIS] and
     * anything it throws, or a slow answer, is an empty list.
     */
    @Suppress("DEPRECATION")
    private fun listRows(receiver: ActivityInfo, authority: String): List<ListedRow> {
        val provider = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
            } else {
                pm.resolveContentProvider(authority, 0)
            }
        } catch (e: RuntimeException) {
            null
        } ?: return emptyList()
        if (provider.packageName != receiver.packageName || !provider.exported || provider.readPermission != RouterApi.PERMISSION_ROUTE_TEXT) return emptyList()
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath(RouterApi.LIST_PATH).build()
        val pending = listQueries.submit<List<ListedRow>> {
            context.contentResolver.query(uri, COLUMNS, null, null, null)?.use { it.rows() }.orEmpty()
        }
        return try {
            pending.get(RouterApi.LIST_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.cancel(true)
            emptyList()
        } catch (e: ExecutionException) {
            emptyList()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }
    }

    private fun Cursor.rows(): List<ListedRow> {
        val columns = COLUMNS.map { getColumnIndex(it) }
        fun text(index: Int): String? = columns[index].takeIf { it >= 0 }?.let { if (getType(it) == Cursor.FIELD_TYPE_STRING) getString(it) else null }
        val rows = mutableListOf<ListedRow>()
        while (rows.size < RouterApi.MAX_LISTED * 2 && moveToNext()) rows += ListedRow(text(0), text(1), text(2), text(3))
        return rows
    }

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

    private companion object {
        val COLUMNS = arrayOf(RouterApi.COLUMN_ID, RouterApi.COLUMN_LABEL, RouterApi.COLUMN_DESCRIPTION, RouterApi.COLUMN_NEEDS)

        // A provider that never answers keeps its thread; a cached pool lets the next query start anyway.
        val listQueries = Executors.newCachedThreadPool { Thread(it, "router-list").apply { isDaemon = true } }
    }
}
