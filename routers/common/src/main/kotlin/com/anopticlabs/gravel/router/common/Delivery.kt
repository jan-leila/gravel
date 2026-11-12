package com.anopticlabs.gravel.router.common

import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Where a recording was made, as Gravel delivered it. */
data class Location(val latitude: Double, val longitude: Double, val accuracyMeters: Float?)

/** A Switch rule in the user's routing that matched on the way here: the switch, and the [text] it matched at [start] until [end] of the delivery's text. */
data class Match(val routerId: String, val routerName: String, val start: Int, val end: Int, val text: String)

/**
 * One delivery from Gravel: the text and the capture id always, the recording time and the
 * location only when this router declared the need and the user granted it (`ROUTER_API.md`).
 * [routerId] names the listed router ([RouterList]) the delivery is for, and is null for the
 * router the app's manifest declares. [captureId] repeats when Gravel delivers the same recording
 * again, so with [routerId] it is the outbox's key. [unmatchedText] and [matches] are what the
 * user's Switch rules matched on the way, absent from a Gravel older than them.
 */
data class Delivery(
    val captureId: String,
    val text: String,
    val recordedAt: String?,
    val location: Location?,
    val routerId: String? = null,
    val unmatchedText: String? = null,
    val matches: List<Match> = emptyList(),
) {
    /**
     * What a sender sends: the text without the words that routed it here ("add a task: buy milk"
     * to "buy milk"), or the whole text when nothing is left of it or Gravel sent no unmatched text.
     */
    val content: String get() = unmatchedText?.takeIf { it.isNotBlank() } ?: text

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
        if (matches.isNotEmpty()) properties.setProperty(KEY_MATCH_COUNT, matches.size.toString())
        matches.forEachIndexed { index, match ->
            properties.setProperty("$KEY_MATCH.$index.router_id", match.routerId)
            properties.setProperty("$KEY_MATCH.$index.router_name", match.routerName)
            properties.setProperty("$KEY_MATCH.$index.start", match.start.toString())
            properties.setProperty("$KEY_MATCH.$index.end", match.end.toString())
            properties.setProperty("$KEY_MATCH.$index.text", match.text)
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
        private const val KEY_MATCH_COUNT = "match_count"
        private const val KEY_MATCH = "match"

        /** More matches than Gravel's walk can make (16 switches in a row); past it the matches are not read. */
        private const val MAX_MATCHES = 16

        /**
         * The delivery the broadcast's extras carry, or null for one this version does not
         * understand: another API version, a missing id or text, a version 2 delivery without a
         * router id of the id grammar, or a location with one coordinate missing. The match
         * extras are optional, so arrays that do not line up leave the matches empty rather than
         * turn the delivery away. The extras come as a map so the reading is testable without Android.
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
            return Delivery(captureId, text, recordedAt, location, routerId, unmatchedText, matches(extras))
        }

        private fun matches(extras: Map<String, Any?>): List<Match> {
            val ids = (extras[RouterContract.EXTRA_MATCH_ROUTER_IDS] as? Array<*>)?.filterIsInstance<String>() ?: return emptyList()
            val names = (extras[RouterContract.EXTRA_MATCH_ROUTER_NAMES] as? Array<*>)?.filterIsInstance<String>() ?: return emptyList()
            val texts = (extras[RouterContract.EXTRA_MATCH_TEXTS] as? Array<*>)?.filterIsInstance<String>() ?: return emptyList()
            val starts = extras[RouterContract.EXTRA_MATCH_STARTS] as? IntArray ?: return emptyList()
            val ends = extras[RouterContract.EXTRA_MATCH_ENDS] as? IntArray ?: return emptyList()
            val count = ids.size
            if (count > MAX_MATCHES || listOf(names.size, texts.size, starts.size, ends.size).any { it != count }) return emptyList()
            return List(count) { Match(ids[it], names[it], starts[it], ends[it], texts[it]) }
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
            val matchCount = properties.getProperty(KEY_MATCH_COUNT)?.toIntOrNull()?.coerceIn(0, MAX_MATCHES) ?: 0
            val matches = (0 until matchCount).mapNotNull { index ->
                fun value(name: String) = properties.getProperty("$KEY_MATCH.$index.$name")
                Match(
                    value("router_id") ?: return@mapNotNull null,
                    value("router_name") ?: return@mapNotNull null,
                    value("start")?.toIntOrNull() ?: return@mapNotNull null,
                    value("end")?.toIntOrNull() ?: return@mapNotNull null,
                    value("text") ?: return@mapNotNull null,
                )
            }
            return Delivery(
                captureId, text, properties.getProperty(KEY_RECORDED_AT), location, properties.getProperty(KEY_ROUTER_ID),
                properties.getProperty(KEY_UNMATCHED_TEXT), matches,
            )
        }
    }
}
