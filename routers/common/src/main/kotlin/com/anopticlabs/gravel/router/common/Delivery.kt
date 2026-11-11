package com.anopticlabs.gravel.router.common

import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/** Where a recording was made, as Gravel delivered it. */
data class Location(val latitude: Double, val longitude: Double, val accuracyMeters: Float?)

/**
 * One delivery from Gravel: the text and the capture id always, the recording time and the
 * location only when this router declared the need and the user granted it (`ROUTER_API.md`).
 * [captureId] repeats when Gravel delivers the same recording again, so it is the outbox's key.
 */
data class Delivery(
    val captureId: String,
    val text: String,
    val recordedAt: String?,
    val location: Location?,
) {
    /** The delivery as the outbox stores it; [read] turns it back. */
    fun write(out: OutputStream) {
        val properties = Properties()
        properties.setProperty(KEY_CAPTURE_ID, captureId)
        properties.setProperty(KEY_TEXT, text)
        recordedAt?.let { properties.setProperty(KEY_RECORDED_AT, it) }
        location?.let {
            properties.setProperty(KEY_LATITUDE, it.latitude.toString())
            properties.setProperty(KEY_LONGITUDE, it.longitude.toString())
            it.accuracyMeters?.let { accuracy -> properties.setProperty(KEY_ACCURACY, accuracy.toString()) }
        }
        properties.store(out, null)
    }

    companion object {
        private const val KEY_CAPTURE_ID = "capture_id"
        private const val KEY_TEXT = "text"
        private const val KEY_RECORDED_AT = "recorded_at"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_ACCURACY = "accuracy_m"

        /**
         * The delivery the broadcast's extras carry, or null for one this version does not
         * understand: another API version, a missing id or text, or a location with one
         * coordinate missing. The extras come as a map so the reading is testable without Android.
         */
        fun from(extras: Map<String, Any?>): Delivery? {
            if (extras[RouterContract.EXTRA_API_VERSION] != RouterContract.API_VERSION) return null
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
            return Delivery(captureId, text, recordedAt, location)
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
            return Delivery(captureId, text, properties.getProperty(KEY_RECORDED_AT), location)
        }
    }
}
