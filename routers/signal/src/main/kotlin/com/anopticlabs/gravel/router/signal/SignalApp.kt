package com.anopticlabs.gravel.router.signal

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

/**
 * Signal's own app has no interface another app could send through, so this one sends through a
 * signal-cli-rest-api relay the user runs (routers/README.md), usually on a LAN without TLS.
 */
class SignalApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val sender: Sender by lazy { SignalSender(settings, Http(allowPrivateCleartext = true)::send) }

    // A wrong address is refused here, where Gravel flags the recording, not found at the send, where it would already be acknowledged.
    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The Signal router is not set up: ${it.label} is missing" }
        return if (Http.allowed(settings[KEY_URL].orEmpty(), allowPrivateCleartext = true)) null else BAD_ADDRESS
    }

    companion object {
        const val KEY_URL = "url"
        const val BAD_ADDRESS = "The relay address has to be https://, or http:// on your own network"
        const val KEY_NUMBER = "number"
        const val KEY_RECIPIENTS = "recipients"
        const val KEY_TOKEN = "token"

        val FIELDS = listOf(
            Field(KEY_URL, "Relay address", "Your signal-cli-rest-api, such as http://signal.lan:8080"),
            Field(KEY_NUMBER, "Your number", "The number the relay is registered with, as +4917..."),
            Field(KEY_RECIPIENTS, "Recipients", "Numbers or group ids, separated by commas"),
            Field(KEY_TOKEN, "Bearer token", "Only if a proxy in front of the relay asks for one; kept sealed", secret = true, required = false),
        )
    }
}
