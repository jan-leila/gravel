package com.anopticlabs.gravel.router.signal

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterListSource
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

/**
 * Signal's own app has no interface another app could send through, so this one sends through a
 * signal-cli-rest-api relay the user runs (routers/README.md), usually on a LAN without TLS.
 */
class SignalApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    private val http by lazy { Http(allowPrivateCleartext = true) }

    override val routerList: RouterList by lazy { RouterList(this) }

    override val sender: Sender by lazy { SignalSender(settings, routerList, http::send) }

    // Contacts are people: none reaches Gravel's settings until the user ticks it here.
    val routers: RouterListSource by lazy {
        RouterListSource.Fetched(
            title = "Contacts",
            intro = "Refresh to read the relay's contacts, then tick the ones Gravel may route to; each ticked contact is a router of its own in Gravel. Gravel sees no contact you have not ticked.",
            sharedByDefault = false,
            fetch = { signalContacts(settings[KEY_URL].orEmpty(), settings[KEY_TOKEN], settings[KEY_NUMBER].orEmpty(), http::send) },
        )
    }

    // A wrong address is refused here, where Gravel flags the recording, not found at the send, where it would already be acknowledged.
    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The Signal router is not set up: ${it.label} is missing" }
        if (delivery.routerId == null && signalRecipients(settings[KEY_RECIPIENTS].orEmpty()).isEmpty()) return NO_RECIPIENTS
        return if (Http.allowed(settings[KEY_URL].orEmpty(), allowPrivateCleartext = true)) null else BAD_ADDRESS
    }

    companion object {
        const val KEY_URL = "url"
        const val BAD_ADDRESS = "The relay address has to be https://, or http:// on your own network"
        const val NO_RECIPIENTS = "The Signal router has no recipients"
        const val KEY_NUMBER = "number"
        const val KEY_RECIPIENTS = "recipients"
        const val KEY_TOKEN = "token"

        /** The prefix of the listed routers' ids, and the value that names the recipient. */
        const val LISTED_PREFIX = "signal"
        const val VALUE_RECIPIENT = "recipient"

        val FIELDS = listOf(
            Field(KEY_URL, "Relay address", "Your signal-cli-rest-api, such as http://signal.lan:8080"),
            Field(KEY_NUMBER, "Your number", "The number the relay is registered with, as +4917..."),
            Field(KEY_RECIPIENTS, "Recipients", "For the \"Send over Signal\" router: numbers or group ids, separated by commas", required = false),
            Field(KEY_TOKEN, "Bearer token", "Only if a proxy in front of the relay asks for one; kept sealed", secret = true, required = false),
        )
    }
}
