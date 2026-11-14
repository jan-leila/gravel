package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException

/**
 * Hands a [RoutedText] to a router that consumes it outside the Notebook (an app, a server).
 * [share] is what goes with the text beyond it ([RouterConfig.shared]); the rest of the payload
 * stays on the phone. Returns once the receiver has taken it; a failure is a
 * [CaptureProcessingException] whose kind says whether the capture waits, is tried again or is flagged.
 */
interface TextDeliverer {
    suspend fun deliver(router: RouterConfig, text: RoutedText, share: Set<Need>)
}

/** Dispatches a delivery to the deliverer bound for the router's kind; a kind with none bound flags the capture. */
class RouterDeliveries(
    private val app: TextDeliverer? = null,
    private val network: TextDeliverer? = null,
    private val watchApp: TextDeliverer? = null,
) : TextDeliverer {
    override suspend fun deliver(router: RouterConfig, text: RoutedText, share: Set<Need>) {
        val deliverer = when (router) {
            is RouterConfig.App -> app
            is RouterConfig.Network -> network
            is RouterConfig.WatchApp -> watchApp
            is RouterConfig.Note, is RouterConfig.Reminder, is RouterConfig.Switch, is RouterConfig.Time, is RouterConfig.Place, is RouterConfig.OpenApp ->
                throw IllegalArgumentException("${router.name} is consumed by the Notebook, not delivered")
        } ?: throw CaptureProcessingException(
            CaptureFailureKind.FLAG, "this build cannot deliver to ${router.name}", reason = CaptureFailureReason.ROUTER_UNAVAILABLE,
        )
        deliverer.deliver(router, text, share)
    }
}
