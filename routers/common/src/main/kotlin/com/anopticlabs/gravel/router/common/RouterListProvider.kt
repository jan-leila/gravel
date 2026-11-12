package com.anopticlabs.gravel.router.common

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * Lists the app's shared [ListedRouter]s to Gravel (`ROUTER_API.md`, Listing routers). The
 * manifest guards it with Gravel's permission like the receiver; the labels name people and
 * channels, so a query under a permission of that name that is not Gravel's own ([GravelSigners])
 * gets an empty list, as a delivery under one gets no answer.
 */
class RouterListProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        if (uri.pathSegments != listOf(RouterContract.LIST_PATH)) return null
        val cursor = MatrixCursor(COLUMNS)
        if (!GravelSigners.definesPermission(context.packageManager, context.packageName)) {
            Log.w(TAG, "a list query was answered empty: the routing permission is not Gravel's")
            return cursor
        }
        val app = context.applicationContext as? RouterApp ?: return cursor
        app.routerList?.shared()?.forEach { cursor.addRow(arrayOf(it.id, it.label, it.description, it.needs)) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "RouterListProvider"
        val COLUMNS = arrayOf(RouterContract.COLUMN_ID, RouterContract.COLUMN_LABEL, RouterContract.COLUMN_DESCRIPTION, RouterContract.COLUMN_NEEDS)
    }
}
