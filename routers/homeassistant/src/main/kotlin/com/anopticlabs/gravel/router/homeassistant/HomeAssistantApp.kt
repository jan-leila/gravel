package com.anopticlabs.gravel.router.homeassistant

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

class HomeAssistantApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    // A Home Assistant on a LAN often has no TLS; cleartext is allowed to a private address only.
    override val sender: Sender by lazy { HomeAssistantSender(settings, Http(allowPrivateCleartext = true)::send) }

    // A wrong address is refused here, where Gravel flags the recording, not found at the send, where it would already be acknowledged.
    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The Home Assistant router is not set up: ${it.label} is missing" }
        return if (Http.allowed(settings[KEY_URL].orEmpty(), allowPrivateCleartext = true)) null else BAD_ADDRESS
    }

    companion object {
        const val KEY_URL = "url"
        const val BAD_ADDRESS = "The Home Assistant address has to be https://, or http:// on your own network"
        const val KEY_TOKEN = "token"
        const val KEY_LANGUAGE = "language"

        val FIELDS = listOf(
            Field(KEY_URL, "Home Assistant address", "https://ha.example.net, or http:// on your own network"),
            Field(KEY_TOKEN, "Long-lived access token", "From your profile in Home Assistant, Security; kept sealed", secret = true),
            Field(KEY_LANGUAGE, "Language", "The language Assist should expect, such as en or de; your phone's when empty", required = false),
        )
    }
}
