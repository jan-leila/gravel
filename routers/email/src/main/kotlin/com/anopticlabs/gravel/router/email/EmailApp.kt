package com.anopticlabs.gravel.router.email

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.ListedRouter
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.RouterIds
import com.anopticlabs.gravel.router.common.RouterList
import com.anopticlabs.gravel.router.common.RouterListSource
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

/**
 * Sends the text as an email through an SMTP server the user sets up. Proton Mail has no API and
 * its Bridge runs on a computer, so for Proton this points at a Bridge instance on a server of the
 * user's, whose self-signed certificate the user pins by its digest (routers/README.md).
 */
class EmailApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val routerList: RouterList by lazy { RouterList(this) }

    override val sender: Sender by lazy { EmailSender(settings, list = routerList) }

    val routers: RouterListSource by lazy {
        RouterListSource.Declared(
            title = "Contacts",
            intro = "Each contact you add is a router of its own in Gravel, mailing what you said to that address from the account above.",
            fields = CONTACT_FIELDS,
            make = ::contactRouter,
        )
    }

    override fun refusal(delivery: Delivery): String? {
        settings.missing()?.let { return "The email router is not set up: ${it.label} is missing" }
        return if (delivery.routerId == null && settings[KEY_TO] == null) NO_DEFAULT else null
    }

    companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_SECURITY = "security"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_SUBJECT = "subject"
        const val KEY_PIN = "pin"
        const val NO_DEFAULT = "No default recipient is set in the email router"

        /** The prefix of the listed routers' ids, the value that holds the contact's address, and the add form's fields. */
        const val LISTED_PREFIX = "email"
        const val VALUE_ADDRESS = "address"
        const val CONTACT_NAME = "name"
        const val CONTACT_ADDRESS = "address"

        val SECURITIES = listOf("STARTTLS", "TLS")

        val FIELDS = listOf(
            Field(KEY_HOST, "SMTP server", "mail.example.net, or your Proton Bridge's address"),
            Field(KEY_PORT, "Port", "587 for STARTTLS, 465 for TLS; Bridge uses 1025 by default"),
            Field(KEY_SECURITY, "Security", required = false, choices = SECURITIES),
            Field(KEY_USERNAME, "Username"),
            Field(KEY_PASSWORD, "Password", "Kept sealed", secret = true),
            Field(KEY_FROM, "From", "The address the server lets this account send as"),
            Field(KEY_TO, "To", "For the \"Send as email\" router; the contacts below get routers of their own", required = false),
            Field(KEY_SUBJECT, "Subject", "\"Voice note\" when empty; the recording time is added when granted", required = false),
            Field(KEY_PIN, "Server certificate SHA-256", "Only for a server with its own certificate, such as a Proton Bridge; a test send with it wrong says so", required = false),
        )

        val CONTACT_FIELDS = listOf(
            Field(CONTACT_NAME, "Name", "What the router is called in Gravel"),
            Field(CONTACT_ADDRESS, "Address"),
        )

        /** A contact's router, under a new id; an address [MailMessage.address] would cut short is no address. */
        fun contactRouter(typed: Map<String, String>): ListedRouter {
            val name = typed[CONTACT_NAME].orEmpty().filterNot { it.isISOControl() }.trim()
            val address = typed[CONTACT_ADDRESS].orEmpty().trim()
            require(name.isNotEmpty()) { "The contact needs a name." }
            require(Regex("[^\\s<>@]+@[^\\s<>@]+").matches(address)) { "$address is not an email address." }
            return ListedRouter(
                id = RouterIds.fresh(LISTED_PREFIX),
                label = "Email $name",
                description = "Mails what you said to $address",
                needs = "recorded_at",
                values = mapOf(VALUE_ADDRESS to address),
            )
        }
    }
}
