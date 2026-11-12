package com.anopticlabs.gravel.router.slack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class SlackSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SlackApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick one of this app's routers in Gravel under Settings > Notebook > Text Routing, and what you say is posted to Slack: to the channel the webhook belongs to, or, with a bot token, to one of the channels below.",
                    fields = SlackApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                    routers = app.routers,
                    routerList = app.routerList,
                )
            }
        }
    }
}
