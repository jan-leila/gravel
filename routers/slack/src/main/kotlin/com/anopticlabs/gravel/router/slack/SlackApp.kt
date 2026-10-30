package com.anopticlabs.gravel.router.slack

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

class SlackApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val sender: Sender by lazy { SlackSender(settings, Http()::send) }

    override fun refusal(delivery: Delivery): String? = settings.missing()?.let { "The Slack router is not set up: ${it.label} is missing" }

    companion object {
        const val KEY_WEBHOOK = "webhook"

        val FIELDS = listOf(
            Field(KEY_WEBHOOK, "Incoming webhook URL", "From Slack's app settings, Incoming Webhooks; it is a secret, and is kept sealed", secret = true),
        )
    }
}
