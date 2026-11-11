package com.anopticlabs.gravel.router.mastodon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class MastodonSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MastodonApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick this router in Gravel under Settings > Notebook > Text Routing, and what you say is posted as a status, followers-only unless you choose otherwise below.",
                    fields = MastodonApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                )
            }
        }
    }
}
