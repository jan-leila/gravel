package com.anopticlabs.gravel.router.osmnote

import com.anopticlabs.gravel.router.common.Delivery
import com.anopticlabs.gravel.router.common.Http
import com.anopticlabs.gravel.router.common.HttpAnswer
import com.anopticlabs.gravel.router.common.HttpRequest
import com.anopticlabs.gravel.router.common.Location
import com.anopticlabs.gravel.router.common.SendResult
import com.anopticlabs.gravel.router.common.Sender
import com.anopticlabs.gravel.router.common.Settings
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** The note: OSM's API 0.6 creates one with a POST carrying the position and the text as query parameters. */
fun osmNoteRequest(server: String, token: String?, location: Location, text: String): HttpRequest {
    val lat = String.format(Locale.ROOT, "%.7f", location.latitude)
    val lon = String.format(Locale.ROOT, "%.7f", location.longitude)
    return HttpRequest(
        method = "POST",
        url = server.trimEnd('/') + "/api/0.6/notes?lat=$lat&lon=$lon&text=${URLEncoder.encode(text, "UTF-8")}",
        headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
    )
}

class OsmNoteSender(private val settings: Settings, private val send: (HttpRequest) -> HttpAnswer) : Sender {
    override fun send(delivery: Delivery): SendResult {
        val location = delivery.location ?: return SendResult.Refused(OsmNoteApp.NO_LOCATION)
        val server = settings[OsmNoteApp.KEY_SERVER] ?: OsmNoteApp.DEFAULT_SERVER
        if (!Http.allowed(server, allowPrivateCleartext = false)) return SendResult.Refused("The API server has to be an https:// address")
        return answer(try {
            send(osmNoteRequest(server, settings[OsmNoteApp.KEY_TOKEN], location, delivery.text))
        } catch (e: IOException) {
            return SendResult.Retry("OpenStreetMap could not be reached: ${e::class.simpleName}")
        })
    }

    companion object {
        fun answer(answer: HttpAnswer): SendResult = when (answer.status) {
            in 200..299 -> SendResult.Sent
            400 -> SendResult.Refused("OpenStreetMap refused the note (HTTP 400)")
            401, 403 -> SendResult.Refused("OpenStreetMap refused the token (HTTP ${answer.status})")
            else -> SendResult.Retry("OpenStreetMap answered HTTP ${answer.status}")
        }
    }
}
