package com.anopticlabs.gravel.router.actual

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
 * Actual Budget's API is a Node library, so this app talks to the actual-http-api relay the user
 * runs beside their Actual server (routers/README.md), usually on a LAN without TLS.
 */
class ActualApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    private val http by lazy { Http(allowPrivateCleartext = true) }

    override val routerList: RouterList by lazy { RouterList(this) }

    override val sender: Sender by lazy { ActualSender(settings, routerList, http::send) }

    val routers: RouterListSource by lazy {
        RouterListSource.Fetched(
            title = "Accounts",
            intro = "Every open account you refresh here is a router of its own in Gravel, booking spends into that account. Untick one to keep it out of Gravel.",
            sharedByDefault = true,
            fetch = { actualAccounts(settings[KEY_URL].orEmpty(), settings[KEY_API_KEY].orEmpty(), settings[KEY_BUDGET].orEmpty(), http::send) },
        )
    }

    // A transaction without an amount is nothing to enter, and no retry hears one; a wrong address is refused here too, where Gravel flags the recording.
    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The Actual router is not set up: ${it.label} is missing" }
        if (delivery.routerId == null && settings[KEY_ACCOUNT] == null) return NO_DEFAULT
        if (!Http.allowed(settings[KEY_URL].orEmpty(), allowPrivateCleartext = true)) return BAD_ADDRESS
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
        const val NO_DEFAULT = "No default account is set in the Actual router"
        const val BAD_ADDRESS = "The relay address has to be https://, or http:// on your own network"

        /** The prefix of the listed routers' ids, and the value that names the account. */
        const val LISTED_PREFIX = "actual"
        const val VALUE_ACCOUNT = "account"

        val FIELDS = listOf(
            Field(KEY_URL, "Relay address", "Your actual-http-api, such as http://budget.lan:5007"),
            Field(KEY_API_KEY, "API key", "The relay's own key (its API_KEY setting), not an Actual password; kept sealed", secret = true),
            Field(KEY_BUDGET, "Budget sync id", "From Actual's Settings, Advanced, Sync ID"),
            Field(KEY_ACCOUNT, "Default account id", "For the \"Add an Actual transaction\" router; the accounts below get routers of their own", required = false),
            Field(KEY_DECIMALS, "Decimals", "Minor digits of your currency, 2 for cents", required = false),
        )
    }
}
