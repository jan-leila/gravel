package com.anopticlabs.gravel.router.mastodon

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

class MastodonApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val sender: Sender by lazy { MastodonSender(settings, Http()::send) }

    override fun refusal(delivery: Delivery): String? = settings.missing()?.let { "The Mastodon router is not set up: ${it.label} is missing" }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_VISIBILITY = "visibility"

        /** Mastodon's names; followers-only first, so a slip of the tongue is not public by default. */
        val VISIBILITIES = listOf("private", "unlisted", "public", "direct")

        val FIELDS = listOf(
            Field(KEY_URL, "Instance address", "https://mastodon.example"),
            Field(KEY_TOKEN, "Access token", "From Preferences, Development, an application with the write:statuses scope; kept sealed", secret = true),
            Field(KEY_VISIBILITY, "Visibility", required = false, choices = VISIBILITIES),
        )
    }
}
