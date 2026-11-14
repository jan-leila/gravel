package com.anopticlabs.gravel.router.common

import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Where a recording was made, as Gravel delivered it. */
data class Location(val latitude: Double, val longitude: Double, val accuracyMeters: Float?)

/**
 * One delivery from Gravel: the text and the capture id always, the recording time and the
 * location only when this router declared the need and the user granted it (`ROUTER_API.md`).
 * [routerId] names the listed router ([RouterList]) the delivery is for, and is null for the
 * router the app's manifest declares. [captureId] repeats when Gravel delivers the same recording
 * again, so with [routerId] it is the outbox's key. [unmatchedText] is the text with the words the
 * user's Switch rules cut taken out, and [fields] the request parameters the user filled from
 * what those rules matched, by the names the manifest's `params` declares; both are absent from a
 * Gravel older than them.
 */
data class Delivery(
    val captureId: String,
    val text: String,
    val recordedAt: String?,
    val location: Location?,
    val routerId: String? = null,
    val unmatchedText: String? = null,
    val fields: Map<String, String> = emptyMap(),
) {
    /**
     * What a sender sends where the user mapped no parameter: the text without the words a rule cut
     * ("add a task: buy milk" to "buy milk"), or the whole text when nothing is left of it or
     * Gravel sent no unmatched text.
     */
    val content: String get() = unmatchedText?.takeIf { it.isNotBlank() } ?: text

    /** The parameter [name] as the user filled it, or null when it was not, or with nothing but spaces. */
    fun field(name: String): String? = fields[name]?.takeIf { it.isNotBlank() }

    /** The delivery as the outbox stores it; [read] turns it back. */
    fun write(out: OutputStream) {
        val properties = Properties()
        properties.setProperty(KEY_CAPTURE_ID, captureId)
        properties.setProperty(KEY_TEXT, text)
        recordedAt?.let { properties.setProperty(KEY_RECORDED_AT, it) }
        routerId?.let { properties.setProperty(KEY_ROUTER_ID, it) }
        location?.let {
            properties.setProperty(KEY_LATITUDE, it.latitude.toString())
            properties.setProperty(KEY_LONGITUDE, it.longitude.toString())
            it.accuracyMeters?.let { accuracy -> properties.setProperty(KEY_ACCURACY, accuracy.toString()) }
        }
        unmatchedText?.let { properties.setProperty(KEY_UNMATCHED_TEXT, it) }
        if (fields.isNotEmpty()) properties.setProperty(KEY_FIELD_COUNT, fields.size.toString())
        fields.entries.forEachIndexed { index, (name, value) ->
            properties.setProperty("$KEY_FIELD.$index.name", name)
            properties.setProperty("$KEY_FIELD.$index.value", value)
        }
        properties.store(out, null)
    }

    companion object {
        private const val KEY_CAPTURE_ID = "capture_id"
        private const val KEY_TEXT = "text"
        private const val KEY_RECORDED_AT = "recorded_at"
        private const val KEY_ROUTER_ID = "router_id"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_ACCURACY = "accuracy_m"
        private const val KEY_UNMATCHED_TEXT = "unmatched_text"
        private const val KEY_FIELD_COUNT = "field_count"
        private const val KEY_FIELD = "field"

        /** As many parameters as Gravel maps for a router; past them, the rest are not read. */
        private const val MAX_FIELDS = 16

        /**
         * The delivery the broadcast's extras carry, or null for one this version does not
         * understand: another API version, a missing id or text, a version 2 delivery without a
         * router id of the id grammar, or a location with one coordinate missing. The field
         * extras are optional, so arrays that do not line up leave the fields empty rather than turn
         * the delivery away; past [MAX_FIELDS], the rest are not read. The extras come as a map so
         * the reading is testable without Android.
         */
        fun from(extras: Map<String, Any?>): Delivery? {
            val routerId = when (extras[RouterContract.EXTRA_API_VERSION]) {
                RouterContract.API_VERSION -> null
                RouterContract.API_VERSION_LISTED -> (extras[RouterContract.EXTRA_ROUTER_ID] as? String)?.takeIf { RouterIds.isRouterId(it) } ?: return null
                else -> return null
            }
            val captureId = extras[RouterContract.EXTRA_CAPTURE_ID] as? String ?: return null
            val text = extras[RouterContract.EXTRA_TEXT] as? String ?: return null
            if (captureId.isEmpty()) return null
            val recordedAt = extras[RouterContract.EXTRA_RECORDED_AT] as? String
            val latitude = extras[RouterContract.EXTRA_LOCATION_LATITUDE] as? Double
            val longitude = extras[RouterContract.EXTRA_LOCATION_LONGITUDE] as? Double
            val location = when {
                latitude == null && longitude == null -> null
                latitude == null || longitude == null -> return null
                else -> Location(latitude, longitude, extras[RouterContract.EXTRA_LOCATION_ACCURACY_METERS] as? Float)
            }
            val unmatchedText = extras[RouterContract.EXTRA_UNMATCHED_TEXT] as? String
            return Delivery(captureId, text, recordedAt, location, routerId, unmatchedText, fields(extras))
        }

        private fun fields(extras: Map<String, Any?>): Map<String, String> {
            val names = extras[RouterContract.EXTRA_FIELD_NAMES] as? Array<*> ?: return emptyMap()
            val values = extras[RouterContract.EXTRA_FIELD_VALUES] as? Array<*> ?: return emptyMap()
            if (names.size != values.size) return emptyMap()
            return names.zip(values).take(MAX_FIELDS).mapNotNull { (name, value) -> if (name is String && value is String) name to value else null }.toMap()
        }

        /** A delivery [write] stored, or null for a file that is not one. */
        fun read(input: InputStream): Delivery? {
            val properties = Properties()
            properties.load(input)
            val captureId = properties.getProperty(KEY_CAPTURE_ID) ?: return null
            val text = properties.getProperty(KEY_TEXT) ?: return null
            val latitude = properties.getProperty(KEY_LATITUDE)?.toDoubleOrNull()
            val longitude = properties.getProperty(KEY_LONGITUDE)?.toDoubleOrNull()
            val location = if (latitude != null && longitude != null) Location(latitude, longitude, properties.getProperty(KEY_ACCURACY)?.toFloatOrNull()) else null
            val fieldCount = properties.getProperty(KEY_FIELD_COUNT)?.toIntOrNull()?.coerceIn(0, MAX_FIELDS) ?: 0
            val fields = (0 until fieldCount).mapNotNull { index ->
                val name = properties.getProperty("$KEY_FIELD.$index.name") ?: return@mapNotNull null
                properties.getProperty("$KEY_FIELD.$index.value")?.let { name to it }
            }.toMap()
            return Delivery(
                captureId, text, properties.getProperty(KEY_RECORDED_AT), location, properties.getProperty(KEY_ROUTER_ID),
                properties.getProperty(KEY_UNMATCHED_TEXT), fields,
            )
        }
    }
}
