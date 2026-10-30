package com.anopticlabs.gravel.router.email

import android.app.Application
import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Field
import com.anopticlabs.gravel.router.common.RouterApp
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings

/**
 * Sends the text as an email through an SMTP server the user sets up. Proton Mail has no API and
 * its Bridge runs on a computer, so for Proton this points at a Bridge instance on a server of the
 * user's, whose self-signed certificate the user pins by its digest (routers/README.md).
 */
class EmailApp : Application(), RouterApp {
    val settings: Settings by lazy { Settings(this, FIELDS) }

    override val sender: Sender by lazy { EmailSender(settings) }

    override fun refusal(delivery: Delivery): String? = settings.missing()?.let { "The email router is not set up: ${it.label} is missing" }

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

        val SECURITIES = listOf("STARTTLS", "TLS")

        val FIELDS = listOf(
            Field(KEY_HOST, "SMTP server", "mail.example.net, or your Proton Bridge's address"),
            Field(KEY_PORT, "Port", "587 for STARTTLS, 465 for TLS; Bridge uses 1025 by default"),
            Field(KEY_SECURITY, "Security", required = false, choices = SECURITIES),
            Field(KEY_USERNAME, "Username"),
            Field(KEY_PASSWORD, "Password", "Kept sealed", secret = true),
            Field(KEY_FROM, "From", "The address the server lets this account send as"),
            Field(KEY_TO, "To"),
            Field(KEY_SUBJECT, "Subject", "\"Voice note\" when empty; the recording time is added when granted", required = false),
            Field(KEY_PIN, "Server certificate SHA-256", "Only for a server with its own certificate, such as a Proton Bridge; a test send with it wrong says so", required = false),
        )
    }
}
