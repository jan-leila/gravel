package com.anopticlabs.gravel.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The app backup: one password-sealed file (the Notebook archive's sealing under [MAGIC]) whose
 * plaintext is a zip of the entries named below. The zip is only read once the sealed stream has
 * been read to its authenticated end, so nothing is applied from a file cut short.
 */
object AppBackupFormat {
    val MAGIC: ByteArray get() = "GBAK".encodeToByteArray()
    const val VERSION = 1
    const val MIME_TYPE = "application/octet-stream"
    const val FILE_PREFIX = "gravel-backup-"
    const val FILE_SUFFIX = ".gbak"

    const val MANIFEST = "manifest.json"
    const val PREFS_DIR = "prefs/"
    const val PKJS_DIR = "pkjs/"
    const val TABLES_DIR = "tables/"
    const val PBW_DIR = "pbw/"
    const val NOTEBOOK = "notebook.gnbk"
    const val ROUTING = "routing.json"

    /** The caps an entry is read under; an entry over its cap is damage, not data. */
    const val MAX_JSON_ENTRY_BYTES = 4L * 1024 * 1024
    const val MAX_PBW_BYTES = 64L * 1024 * 1024

    /**
     * Keys of the app's default preferences file that are the user's choices. Everything else in
     * that file is a token, a cache, a one-shot flag or per-watch sync state, and stays behind.
     */
    val DEFAULT_PREF_KEYS = setOf(
        "libpebble.settings",
        "coreapp.config",
        "current_theme",
        "health_platform_sync_enabled",
        "enable_experimental_devices",
        "stt_server_dictation_lane",
        "boot_config_url",
        "showDebugOptions",
    )

    /** The Notebook's preferences file and its user-chosen keys (NotebookPreferences). */
    const val NOTEBOOK_PREFS_FILE = "notebook"
    val NOTEBOOK_PREF_KEYS = setOf(
        "swipe_snooze_minutes",
        "history_retention_days",
        "quick_remind_minutes",
        "trigger_enabled",
        "trigger_word",
        "text_model_id",
        "tab_shown",
        "phone_calendar_shown",
        "phone_calendars",
        "interrupt_long_captures",
        "long_captures_wait_for_power",
        "ring_location_seconds",
    )

    /**
     * The tables saved, per database, in the order they are restored: a table after the ones it
     * has foreign keys to, since replacing a parent row cascades to its children. Generated data
     * (timeline, notification history, weather, per-watch sync state, known watches) stays behind.
     */
    val LIBPEBBLE_TABLES = listOf(
        "LockerEntryEntity",
        "LockerAppPermission",
        "NotificationAppItemEntity",
        "NotificationRuleEntity",
        "ContactEntity",
        "VibePatternEntity",
        "CalendarEntity",
        "WatchPrefItemEntity",
        "HealthSettingsEntryEntity",
        "AppPrefsEntryEntity",
        "health_data",
        "overlay_data",
    )
    val CORE_TABLES = listOf(
        "AppstoreSource",
        "AppstoreCollection",
        "HeartEntity",
        "WeatherLocationEntity",
    )

    const val LIBPEBBLE_DB = "libpebble3"
    const val CORE_DB = "coreapp"

    fun tableEntry(database: String, table: String) = "$TABLES_DIR$database/$table.jsonl"
    fun prefsEntry(file: String) = "$PREFS_DIR$file.json"
    fun pkjsEntry(appId: String) = "$PKJS_DIR$appId.json"
    fun pbwEntry(appId: String) = "$PBW_DIR$appId.pbw"

    val json = Json { ignoreUnknownKeys = true }
}

@Serializable
data class AppBackupManifest(
    @SerialName("v") val version: Int,
    val createdAtMs: Long,
    val appVersion: String,
    /** Rows written per table entry. */
    val tables: Map<String, Int>,
    /** Sideloaded apps whose PBW the phone no longer had; they are in the locker rows but not restorable. */
    val missingPbws: List<String> = emptyList(),
    val notebook: Boolean,
)

/**
 * SharedPreferences values as JSON, typed so they read back as the same type: a string set as an
 * array, a float as an object, since JSON alone cannot tell an int from a long or a float.
 */
object PrefValues {
    fun encode(values: Map<String, Any?>): JsonObject = JsonObject(
        values.mapNotNull { (key, value) -> encodeValue(value)?.let { key to it } }.toMap(),
    )

    /** The values [encode] wrote; one it cannot read is left out, so the rest still restore. */
    fun decode(json: JsonObject): Map<String, Any> = json.mapNotNull { (key, element) ->
        decodeValue(element)?.let { key to it }
    }.toMap()

    private fun encodeValue(value: Any?): JsonElement? = when (value) {
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonObject(mapOf(TYPE to JsonPrimitive(INT), VALUE to JsonPrimitive(value)))
        is Long -> JsonObject(mapOf(TYPE to JsonPrimitive(LONG), VALUE to JsonPrimitive(value)))
        is Float -> JsonObject(mapOf(TYPE to JsonPrimitive(FLOAT), VALUE to JsonPrimitive(value)))
        is Set<*> -> JsonArray(value.filterIsInstance<String>().sorted().map { JsonPrimitive(it) })
        else -> null
    }

    private fun decodeValue(element: JsonElement): Any? = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
        is JsonObject -> {
            val value = element[VALUE] as? JsonPrimitive
            when ((element[TYPE] as? JsonPrimitive)?.content) {
                INT -> value?.intOrNull
                LONG -> value?.longOrNull
                FLOAT -> value?.doubleOrNull?.toFloat()
                else -> null
            }
        }
        is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull
        else -> null
    }

    private const val TYPE = "t"
    private const val VALUE = "v"
    private const val INT = "int"
    private const val LONG = "long"
    private const val FLOAT = "float"
}
