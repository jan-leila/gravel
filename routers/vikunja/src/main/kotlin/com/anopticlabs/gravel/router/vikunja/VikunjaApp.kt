package com.anopticlabs.gravel.router.vikunja

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

class VikunjaApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    // A self-hosted Vikunja on a LAN may have no TLS; cleartext is allowed to a private address only.
    override val sender: Sender by lazy { VikunjaSender(settings, Http(allowPrivateCleartext = true)::send) }

    override fun refusal(delivery: Delivery): String? = settings.missing()?.let { "The Vikunja router is not set up: ${it.label} is missing" }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_PROJECT = "project"

        val FIELDS = listOf(
            Field(KEY_URL, "Vikunja address", "https://tasks.example.net, or http:// on your own network"),
            Field(KEY_TOKEN, "API token", "From Settings, API Tokens in Vikunja, with tasks and projects permissions; kept sealed", secret = true),
            Field(KEY_PROJECT, "Project id", "The number in the project's address"),
        )
    }
}
