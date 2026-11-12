package com.anopticlabs.gravel.router.homeassistant

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterListSource
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

/** The declared router hands the words to Assist; the listed ones each call one action the user set up here. */
class HomeAssistantApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val routerList: RouterList by lazy { RouterList(this) }

    // A Home Assistant on a LAN often has no TLS; cleartext is allowed to a private address only.
    override val sender: Sender by lazy { HomeAssistantSender(settings, routerList, Http(allowPrivateCleartext = true)::send) }

    val routers: RouterListSource by lazy {
        RouterListSource.Declared(
            title = "Device actions",
            intro = "Each action you add is a router of its own in Gravel that calls one Home Assistant action, such as light.turn_on on light.kitchen, whatever you said; name a data field to hand it your words too, such as message for a notify action.",
            fields = ACTION_FIELDS,
            make = ::actionRouter,
        )
    }

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

        /** The prefix of the listed routers' ids, and the add form's fields, which are also the values an action keeps. */
        const val LISTED_PREFIX = "homeassistant"
        const val ACTION_NAME = "name"
        const val ACTION_SERVICE = "service"
        const val ACTION_ENTITIES = "entities"
        const val ACTION_TEXT_FIELD = "text_field"

        val FIELDS = listOf(
            Field(KEY_URL, "Home Assistant address", "https://ha.example.net, or http:// on your own network"),
            Field(KEY_TOKEN, "Long-lived access token", "From your profile in Home Assistant, Security; kept sealed", secret = true),
            Field(KEY_LANGUAGE, "Language", "The language Assist should expect, such as en or de; your phone's when empty", required = false),
        )

        val ACTION_FIELDS = listOf(
            Field(ACTION_NAME, "Name", "What the router is called in Gravel, such as Kitchen light on"),
            Field(ACTION_SERVICE, "Action", "domain.action, such as light.turn_on"),
            Field(ACTION_ENTITIES, "Entities", "Entity ids, separated by commas, such as light.kitchen", required = false),
            Field(ACTION_TEXT_FIELD, "Data field for your words", "Such as message; none sends no words", required = false),
        )
    }
}
