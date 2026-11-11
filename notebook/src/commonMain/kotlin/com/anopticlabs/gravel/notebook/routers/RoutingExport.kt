package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The file Settings > Notebook > Text Routing exports and imports: the whole routing and the
 * user's trust changes, as one JSON document. A stored format: the version moves when a field
 * changes meaning. Bearer tokens are in it in clear, so the screen says so before exporting.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RoutingExport(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("v") val version: Int = VERSION,
    val routing: RoutingConfig,
    val trust: TrustEntries = TrustEntries.EMPTY,
) {
    companion object {
        const val VERSION = 1
        const val FILE_NAME = "gravel-text-routing.json"
        const val MIME_TYPE = "application/json"

        /** Far past the largest routing and trust list the caps allow. */
        const val MAX_CHARS = 256 * 1024

        private val pretty = Json(NotebookJson) { prettyPrint = true }

        fun encode(routing: RoutingConfig, trust: TrustEntries): String =
            pretty.encodeToString(serializer(), RoutingExport(routing = routing, trust = trust.cleaned()))

        /** The export [json] holds, validated; refused with a sentence the screen can show. */
        fun decode(json: String): RoutingExport {
            require(json.length <= MAX_CHARS) { "The file is too large to be a routing export." }
            val parsed = try {
                NotebookJson.decodeFromString(serializer(), json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("The file is not a routing export.", e)
            }
            require(parsed.version == VERSION) { "The file was exported by a newer version of Gravel." }
            parsed.routing.requireWellFormed()
            return parsed.copy(trust = parsed.trust.cleaned())
        }
    }
}
