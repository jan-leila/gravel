package com.anopticlabs.gravel.router.common

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON answer to a list fetch. Anything else (no 2xx, a body cut at the request's
 * [HttpRequest.answerLimit], not JSON) is an IOException whose message names [service] and says
 * what came back, for the settings screen to show.
 */
@Throws(IOException::class)
fun fetchJson(service: String, send: (HttpRequest) -> HttpAnswer, request: HttpRequest): JsonElement {
    val answer = send(request)
    if (!answer.ok) throw IOException(answer.addressReason(service) ?: "$service answered HTTP ${answer.status}")
    return try {
        Json.parseToJsonElement(answer.body)
    } catch (e: SerializationException) {
        throw IOException("$service did not answer with a list", e)
    }
}

/** The string at [key], or null for none or another kind of value. */
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** The value at [key] as written, a number or a string, or null. */
fun JsonObject.scalar(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
