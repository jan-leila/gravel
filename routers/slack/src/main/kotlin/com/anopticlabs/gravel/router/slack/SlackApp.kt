package com.anopticlabs.gravel.router.slack

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterListSource
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import java.io.IOException

/**
 * The declared router posts through an incoming webhook, which Slack binds to one channel; the
 * listed routers, one per channel the app's bot was added to, post through the bot token.
 */
class SlackApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    private val http by lazy { Http() }

    override val routerList: RouterList by lazy { RouterList(this) }

    override val sender: Sender by lazy { SlackSender(settings, routerList, http::send) }

    val routers: RouterListSource by lazy {
        RouterListSource.Fetched(
            title = "Channels",
            intro = "Every channel the bot was added to is a router of its own in Gravel once you refresh here. Untick one to keep it out of Gravel.",
            sharedByDefault = true,
            fetch = { slackChannels(settings[KEY_BOT_TOKEN] ?: throw IOException(NO_BOT_TOKEN), http::send) },
        )
    }

    // A wrong address is refused here, where Gravel flags the recording, not found at the send, where it would already be acknowledged.
    override fun refusal(delivery: Delivery): String? {
        if (delivery.routerId != null) return if (settings[KEY_BOT_TOKEN] == null) NO_BOT_TOKEN else null
        val webhook = settings[KEY_WEBHOOK] ?: return "The Slack router is not set up: ${FIELDS.first().label} is missing"
        return if (Http.allowed(webhook, allowPrivateCleartext = false)) null else BAD_ADDRESS
    }

    companion object {
        const val KEY_WEBHOOK = "webhook"
        const val KEY_BOT_TOKEN = "bot_token"
        const val BAD_ADDRESS = "The webhook address is not an https:// address"
        const val NO_BOT_TOKEN = "The Slack router has no bot token"

        /** The prefix of the listed routers' ids, and the value that names the channel. */
        const val LISTED_PREFIX = "slack"
        const val VALUE_CHANNEL = "channel"

        val FIELDS = listOf(
            Field(KEY_WEBHOOK, "Incoming webhook URL", "For the \"Post to Slack\" router: from Slack's app settings, Incoming Webhooks; it is a secret, and is kept sealed", secret = true, required = false),
            Field(KEY_BOT_TOKEN, "Bot token", "For the channel routers: xoxb-..., from OAuth & Permissions, with chat:write, channels:read and groups:read; kept sealed", secret = true, required = false),
        )
    }
}
