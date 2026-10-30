package com.anopticlabs.gravel.router.actual

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

/**
 * Actual Budget's API is a Node library, so this app talks to the actual-http-api relay the user
 * runs beside their Actual server (routers/README.md), usually on a LAN without TLS.
 */
class ActualApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val sender: Sender by lazy { ActualSender(settings, Http(allowPrivateCleartext = true)::send) }

    // A transaction without an amount is nothing to enter, and no retry hears one.
    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The Actual router is not set up: ${it.label} is missing" }
        return if (TextTransactions.parse(delivery.text, decimals()) == null) NO_AMOUNT else null
    }

    fun decimals(): Int = settings[KEY_DECIMALS]?.toIntOrNull()?.coerceIn(0, 4) ?: 2

    companion object {
        const val KEY_URL = "url"
        const val KEY_API_KEY = "api_key"
        const val KEY_BUDGET = "budget"
        const val KEY_ACCOUNT = "account"
        const val KEY_DECIMALS = "decimals"
        const val NO_AMOUNT = "No amount heard"

        val FIELDS = listOf(
            Field(KEY_URL, "Relay address", "Your actual-http-api, such as http://budget.lan:5007"),
            Field(KEY_API_KEY, "API key", "The relay's own key (its API_KEY setting), not an Actual password; kept sealed", secret = true),
            Field(KEY_BUDGET, "Budget sync id", "From Actual's Settings, Advanced, Sync ID"),
            Field(KEY_ACCOUNT, "Account id", "The account the transactions go into"),
            Field(KEY_DECIMALS, "Decimals", "Minor digits of your currency, 2 for cents", required = false),
        )
    }
}
