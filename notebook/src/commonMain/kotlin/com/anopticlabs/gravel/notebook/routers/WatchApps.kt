package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.storage.NotebookJson

/** A watch app in the locker: its UUID, lowercase with dashes, and its name there. */
data class WatchAppInfo(val uuid: String, val name: String)

/** A router the watch app [app] declared with `Pebble.setRouters`: [id] names it within the app. */
data class WatchAppTarget(val app: WatchAppInfo, val id: String, val name: String, val description: String?, val params: List<String>)

enum class WatchAppSend {
    /** The app's JavaScript has the text. */
    DELIVERED,

    /** The app is no longer in the locker. */
    GONE,

    /** No watch is connected to open the app on. */
    NO_WATCH,

    /** The app was opened and its JavaScript did not take the text in time. */
    NOT_TAKEN,
}

/** The watch's apps as the text routing needs them; the app binds it to the watch library. No function throws. */
interface WatchApps {
    /** The UUID of the app open on the connected watch, or null with no watch connected. */
    suspend fun open(): String?

    suspend fun installed(): List<WatchAppInfo>

    /** The routers the installed watch apps declared. */
    suspend fun targets(): List<WatchAppTarget>

    /** Opens [app] on the watch and hands its JavaScript [payload], a JSON object, for its router [router]. */
    suspend fun send(app: String, router: String, payload: String): WatchAppSend

    companion object {
        val NONE = object : WatchApps {
            override suspend fun open(): String? = null
            override suspend fun installed(): List<WatchAppInfo> = emptyList()
            override suspend fun targets(): List<WatchAppTarget> = emptyList()
            override suspend fun send(app: String, router: String, payload: String) = WatchAppSend.GONE
        }
    }
}

/**
 * Delivers to a Watch app router: the body a Network router posts ([NetworkPayload]), handed to
 * the app's JavaScript. An app gone from the locker flags the capture; with no watch connected it
 * waits; an app that was opened and did not take the text is tried again.
 */
class WatchAppRouterDelivery(private val watchApps: WatchApps) : TextDeliverer {
    override suspend fun deliver(router: RouterConfig, text: RoutedText, share: Set<Need>) {
        require(router is RouterConfig.WatchApp) { "${router.name} is not a watch app router" }
        val body = NotebookJson.encodeToString(NetworkPayload.serializer(), NetworkPayload.of(text, share, router.fieldsFor(text)))
        when (watchApps.send(router.app, router.router, body)) {
            WatchAppSend.DELIVERED -> Unit
            WatchAppSend.GONE -> throw CaptureProcessingException(
                CaptureFailureKind.FLAG, "the watch app behind ${router.name} is no longer installed", reason = CaptureFailureReason.ROUTER_UNAVAILABLE,
            )
            WatchAppSend.NO_WATCH -> throw CaptureProcessingException(CaptureFailureKind.WAIT, "${router.name} waits for a watch to be connected")
            WatchAppSend.NOT_TAKEN -> throw CaptureProcessingException(CaptureFailureKind.RETRY, "the watch app behind ${router.name} did not take the text")
        }
    }
}
