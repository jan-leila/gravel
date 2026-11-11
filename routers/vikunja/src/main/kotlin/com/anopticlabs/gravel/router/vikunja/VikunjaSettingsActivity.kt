package com.anopticlabs.gravel.router.vikunja

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class VikunjaSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VikunjaApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick this router in Gravel under Settings > Notebook > Text Routing, and what you say becomes a task in the project below, titled with your words.",
                    fields = VikunjaApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                )
            }
        }
    }
}
