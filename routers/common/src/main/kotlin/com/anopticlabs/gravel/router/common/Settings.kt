package com.anopticlabs.gravel.router.common

import android.content.Context
import android.content.SharedPreferences

/** One setting the app asks the user for; [secret] ones are sealed at rest and shown as a password. */
data class Field(
    val key: String,
    val label: String,
    val hint: String? = null,
    val secret: Boolean = false,
    val required: Boolean = true,
    val choices: List<String>? = null,
)

/** What a sender reads: the value of a setting, or null when it is empty. */
fun interface SettingValues {
    operator fun get(key: String): String?
}

/** The app's settings, in its own preferences; secrets go through [Secrets]. */
class Settings(context: Context, private val fields: List<Field>, private val secrets: Secrets = Secrets()) : SettingValues {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override operator fun get(key: String): String? {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotEmpty() } ?: return null
        return if (fields.first { it.key == key }.secret) secrets.open(raw) else raw
    }

    operator fun set(key: String, value: String) {
        val trimmed = value.trim()
        val stored = if (trimmed.isEmpty()) "" else if (fields.first { it.key == key }.secret) secrets.seal(trimmed) else trimmed
        prefs.edit().putString(key, stored).apply()
    }

    /** The first required field with no value, for the receiver's refusal and the screen's hint. */
    fun missing(): Field? = fields.firstOrNull { it.required && get(it.key) == null }

    private companion object {
        const val FILE_NAME = "router_settings"
    }
}
