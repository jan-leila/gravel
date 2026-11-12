package com.anopticlabs.gravel.router.email

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class EmailSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmailApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick one of this app's routers in Gravel under Settings > Notebook > Text Routing, and what you say is sent as an email from the account below, to the recipient set here or to one of the contacts you add. For Proton Mail, point it at a Proton Bridge you run on a server and pin the Bridge's certificate.",
                    fields = EmailApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                    routers = app.routers,
                    routerList = app.routerList,
                )
            }
        }
    }
}
