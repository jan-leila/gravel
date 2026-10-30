package com.anopticlabs.gravel.notebook.routers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Sends one delivery to a router receiver and returns the result code the receiver left; the caller bounds the wait. */
fun interface RouterBroadcastSender {
    suspend fun send(packageName: String, receiverClass: String, text: RoutedText): Int
}

/**
 * Delivers to an app's router. The receiver is resolved and checked against the config's ref and
 * its app's trust at delivery time, so a package replaced under its name by another signer, a
 * receiver that now declares a different router, or a trust the user withdrew since the router was
 * picked refuses the delivery; then one broadcast, which the
 * receiver acknowledges with [RouterApi.RESULT_ACKNOWLEDGED] within [waitMillis]. No
 * acknowledgement is a retry: the receiver may be slow or not running yet.
 */
class AppRouterDelivery(
    private val installed: InstalledRouters,
    private val trusted: () -> Set<String>,
    private val sender: RouterBroadcastSender,
    private val waitMillis: Long = RouterApi.DELIVERY_WAIT_MILLIS,
) : TextDeliverer {
    override suspend fun deliver(router: RouterConfig, text: RoutedText) {
        require(router is RouterConfig.App) { "${router.name} is not an app router" }
        val receiver = installed.resolve(router.packageName, router.receiverClass)
            ?: unavailable("${router.name} is not installed, or its app no longer declares that router")
        if (receiver.id != router.id) unavailable("the app behind ${router.name} now declares a different router there")
        // The namespace is the signer the user saw when picking; a rotated key still answers for it through the lineage.
        if (receiver.namespace != router.namespace && !installed.signedBy(receiver.packageName, router.namespace)) {
            unavailable("the app behind ${router.name} is signed by a different key than when it was picked")
        }
        val trusted = RouterTrust.isTrusted(receiver.namespace, trusted()) { installed.signedBy(receiver.packageName, it) }
        if (!trusted) unavailable("the app behind ${router.name} is not trusted")
        val code = try {
            withTimeout(waitMillis) { sender.send(receiver.packageName, receiver.receiverClass, text) }
        } catch (e: TimeoutCancellationException) {
            throw CaptureProcessingException(CaptureFailureKind.RETRY, "${router.name} did not answer in time", e)
        }
        if (code != RouterApi.RESULT_ACKNOWLEDGED) throw CaptureProcessingException(CaptureFailureKind.RETRY, "${router.name} did not take the text")
    }

    private fun unavailable(why: String): Nothing =
        throw CaptureProcessingException(CaptureFailureKind.FLAG, why, reason = CaptureFailureReason.ROUTER_UNAVAILABLE)
}

/**
 * The sender on Android: an explicit ordered broadcast on the foreground queue (the receiver gets
 * the foreground timeout, shorter than [RouterApi.DELIVERY_WAIT_MILLIS], so an answer never comes
 * after the wait gave up), its result code read by a result receiver. The extras are the router
 * API's payload and nothing else.
 */
class AndroidRouterBroadcastSender(private val context: Context) : RouterBroadcastSender {
    override suspend fun send(packageName: String, receiverClass: String, text: RoutedText): Int = suspendCancellableCoroutine { continuation ->
        val intent = Intent(RouterApi.ACTION_ROUTE_TEXT)
            .setComponent(ComponentName(packageName, receiverClass))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(RouterApi.EXTRA_API_VERSION, RouterApi.VERSION)
            .putExtra(RouterApi.EXTRA_CAPTURE_ID, text.captureId)
            .putExtra(RouterApi.EXTRA_RECORDED_AT, text.recordedAt)
            .putExtra(RouterApi.EXTRA_TEXT, text.text)
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (continuation.isActive) continuation.resume(resultCode)
            }
        }
        context.sendOrderedBroadcast(intent, null, result, null, Activity.RESULT_CANCELED, null, null)
    }
}
