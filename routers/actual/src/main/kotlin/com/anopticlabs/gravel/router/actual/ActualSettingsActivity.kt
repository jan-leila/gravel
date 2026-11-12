package com.anopticlabs.gravel.router.actual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anopticlabs.gravel.router.common.SettingsScreen

class ActualSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ActualApp
        setContent {
            MaterialTheme {
                SettingsScreen(
                    title = getString(R.string.app_name),
                    intro = "Pick one of this app's routers in Gravel under Settings > Notebook > Text Routing, and \"12 50 at the bakery for bread\" becomes a 12.50 spend at the bakery with your words as the note, in the default account or one of the accounts below. The first number is the amount; the words after \"at\" or \"to\" are the payee. It talks to the actual-http-api relay beside your Actual server.",
                    fields = ActualApp.FIELDS,
                    settings = app.settings,
                    sender = app.sender,
                    routers = app.routers,
                    routerList = app.routerList,
                )
            }
        }
    }
}
