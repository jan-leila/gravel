package com.anopticlabs.gravel.router.vikunja

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterListSource
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

class VikunjaApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    // A self-hosted Vikunja on a LAN may have no TLS; cleartext is allowed to a private address only.
    private val http by lazy { Http(allowPrivateCleartext = true) }

    override val routerList: RouterList by lazy { RouterList(this) }

    override val sender: Sender by lazy { VikunjaSender(settings, routerList, http::send) }

    val routers: RouterListSource by lazy {
        RouterListSource.Fetched(
            title = "Projects",
            intro = "Every project you refresh here is a router of its own in Gravel, adding tasks to that project. Untick one to keep it out of Gravel.",
            sharedByDefault = true,
            fetch = { vikunjaProjects(settings[KEY_URL].orEmpty(), settings[KEY_TOKEN].orEmpty(), http::send) },
        )
    }

    // A wrong address is refused here, where Gravel flags the recording, not found at the send, where it would already be acknowledged.
    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The Vikunja router is not set up: ${it.label} is missing" }
        if (delivery.routerId == null && settings[KEY_PROJECT] == null) return NO_DEFAULT
        return if (Http.allowed(settings[KEY_URL].orEmpty(), allowPrivateCleartext = true)) null else BAD_ADDRESS
    }

    companion object {
        const val KEY_URL = "url"
        const val BAD_ADDRESS = "The Vikunja address has to be https://, or http:// on your own network"
        const val NO_DEFAULT = "No default project is set in the Vikunja router"
        const val KEY_TOKEN = "token"
        const val KEY_PROJECT = "project"

        /** The prefix of the listed routers' ids, and the value that names the project. */
        const val LISTED_PREFIX = "vikunja"
        const val VALUE_PROJECT = "project"

        val FIELDS = listOf(
            Field(KEY_URL, "Vikunja address", "https://tasks.example.net, or http:// on your own network"),
            Field(KEY_TOKEN, "API token", "From Settings, API Tokens in Vikunja, with tasks and projects permissions; kept sealed", secret = true),
            Field(KEY_PROJECT, "Default project id", "The number in the project's address, for the \"Add a Vikunja task\" router", required = false),
        )
    }
}
