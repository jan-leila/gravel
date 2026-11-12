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
                    intro = "Pick one of this app's routers in Gravel under Settings > Notebook > Text Routing, and what you say becomes a task titled with your words, in the default project or in one of the projects below.",
                    fields = VikunjaApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                    routers = app.routers,
                    routerList = app.routerList,
                )
            }
        }
    }
}
