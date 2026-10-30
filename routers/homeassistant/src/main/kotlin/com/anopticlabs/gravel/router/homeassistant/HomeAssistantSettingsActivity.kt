package com.anopticlabs.gravel.router.homeassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class HomeAssistantSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HomeAssistantApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick this router in Gravel under Settings > Notebook > Text Routing, and what you say goes to Home Assistant's Assist as a sentence: \"turn off the kitchen lights\" does what it says.",
                    fields = HomeAssistantApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                )
            }
        }
    }
}
