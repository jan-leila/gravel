package com.anopticlabs.gravel.router.common

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException

/**
 * Takes a delivery from Gravel: puts it in the outbox, asks the worker to send it, and
 * acknowledges, all within the receiver's budget. The app's [RouterApp.refusal] can turn a
 * delivery down at once, with a reason Gravel shows the user. A delivery this version cannot read
 * gets no answer, so Gravel tries again; a later version of this app may read it. A broadcast
 * that reaches the receiver under a permission of the right name that is not Gravel's own
 * ([GravelSigners]) is dropped without an answer.
 */
open class RouterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RouterContract.ACTION_ROUTE_TEXT) return
        if (!GravelSigners.definesPermission(context.packageManager, context.packageName)) {
            Log.w(TAG, "a delivery was dropped: the routing permission is not Gravel's")
            return
        }
        val extras = intent.extras ?: return
        val delivery = Delivery.from(extras.keySet().associateWith { @Suppress("DEPRECATION") extras.get(it) }) ?: run {
            Log.w(TAG, "a delivery this version cannot read was left for Gravel to try again")
            return
        }
        val app = context.applicationContext as? RouterApp
        (listedRefusal(app, delivery) ?: app?.refusal(delivery))?.let { reason ->
            resultCode = RouterContract.RESULT_REFUSED
            resultData = reason.take(RouterContract.MAX_REFUSAL_CHARS)
            return
        }
        try {
            Outbox(context).add(delivery)
        } catch (e: IOException) {
            Log.w(TAG, "a delivery could not be kept: ${e::class.simpleName}")
            return
        }
        OutboxWorker.enqueue(context)
        resultCode = Activity.RESULT_OK
    }

    companion object {
        private const val TAG = "RouterReceiver"
        const val NOT_LISTED = "This router is no longer listed in the app; refresh or pick another in Gravel"

        /** A delivery for a listed router the app no longer shares with Gravel is refused: Gravel saw an older list. */
        fun listedRefusal(app: RouterApp?, delivery: Delivery): String? {
            val id = delivery.routerId ?: return null
            return if (app?.routerList?.find(id)?.shared == true) null else NOT_LISTED
        }
    }
}
