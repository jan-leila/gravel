package com.anopticlabs.gravel.router.osmnote

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

class OsmNoteApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val sender: Sender by lazy { OsmNoteSender(settings, Http()::send) }

    // A note has a place or is nothing: a recording without one is refused at once, with the two reasons it can lack one.
    override fun refusal(delivery: Delivery): String? = when {
        delivery.location == null -> NO_LOCATION
        !Http.allowed(settings[KEY_SERVER] ?: DEFAULT_SERVER, allowPrivateCleartext = false) -> BAD_ADDRESS
        else -> null
    }

    companion object {
        const val KEY_SERVER = "server"
        const val KEY_TOKEN = "token"
        const val DEFAULT_SERVER = "https://api.openstreetmap.org"
        const val BAD_ADDRESS = "The API server has to be an https:// address"
        const val NO_LOCATION = "The recording has no location, or this router was not allowed to see it"

        val FIELDS = listOf(
            Field(KEY_SERVER, "API server", "api.openstreetmap.org when empty; a dev server for trying it out", required = false),
            Field(KEY_TOKEN, "Access token", "An OAuth 2 access token for your OSM account, so the note is yours; anonymous when empty", secret = true, required = false),
        )
    }
}
