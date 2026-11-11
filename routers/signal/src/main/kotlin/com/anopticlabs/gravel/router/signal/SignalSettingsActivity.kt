package com.anopticlabs.gravel.router.signal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class SignalSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SignalApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick this router in Gravel under Settings > Notebook > Text Routing, and what you say is sent as a Signal message through the signal-cli-rest-api relay you run; Signal's own app offers no way in.",
                    fields = SignalApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                )
            }
        }
    }
}
