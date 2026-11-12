package com.anopticlabs.gravel.router.common

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One router the app lists to Gravel beside the one its manifest declares: a project, a channel, an
 * account or a contact. [values] is what the sender needs to reach it (the service's id for it, an
 * address), never shown to Gravel; [needs] is the router API's `needs` value. Only a [shared] one
 * is listed to Gravel.
 */
data class ListedRouter(
    val id: String,
    val label: String,
    val description: String? = null,
    val needs: String? = null,
    val values: Map<String, String> = emptyMap(),
    val shared: Boolean = true,
)

/**
 * The app's listed routers, in one JSON file under its private files, written whole or not at all.
 * A refresh from the service ([merge]) keeps what the user decided about a router it lists again.
 */
class RouterList(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    @Synchronized
    fun all(): List<ListedRouter> = try {
        if (!file.isFile) emptyList() else decode(file.readText())
    } catch (e: IOException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** What Gravel sees. */
    fun shared(): List<ListedRouter> = all().filter { it.shared }

    fun find(id: String): ListedRouter? = all().firstOrNull { it.id == id }

    @Synchronized
    @Throws(IOException::class)
    fun replace(routers: List<ListedRouter>) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("router list directory not created") }
        val staging = File(file.path + ".tmp")
        FileOutputStream(staging).use { out ->
            out.write(encode(routers).encodeToByteArray())
            out.fd.sync()
        }
        if (!staging.renameTo(file)) {
            staging.delete()
            throw IOException("router list not renamed")
        }
    }

    /** The service's list now: a router listed before keeps its sharing, a new one gets [sharedByDefault], one no longer listed goes. */
    @Synchronized
    @Throws(IOException::class)
    fun merge(fetched: List<ListedRouter>, sharedByDefault: Boolean) {
        val before = all().associateBy { it.id }
        replace(fetched.distinctBy { it.id }.map { it.copy(shared = before[it.id]?.shared ?: sharedByDefault) })
    }

    @Synchronized
    @Throws(IOException::class)
    fun setShared(id: String, shared: Boolean) = replace(all().map { if (it.id == id) it.copy(shared = shared) else it })

    @Synchronized
    @Throws(IOException::class)
    fun add(router: ListedRouter) = replace(all().filter { it.id != router.id } + router)

    @Synchronized
    @Throws(IOException::class)
    fun remove(id: String) = replace(all().filter { it.id != id })

    companion object {
        const val FILE_NAME = "router_list.json"

        fun encode(routers: List<ListedRouter>): String = JsonArray(routers.map { router ->
            JsonObject(buildMap {
                put("id", JsonPrimitive(router.id))
                put("label", JsonPrimitive(router.label))
                router.description?.let { put("description", JsonPrimitive(it)) }
                router.needs?.let { put("needs", JsonPrimitive(it)) }
                put("values", JsonObject(router.values.mapValues { JsonPrimitive(it.value) }))
                put("shared", JsonPrimitive(router.shared))
            })
        }).toString()

        /** The list [encode] wrote; an entry that is not a router of the id grammar is left out. */
        fun decode(text: String): List<ListedRouter> {
            val array = Json.parseToJsonElement(text) as? JsonArray ?: throw IllegalArgumentException("not a router list")
            return array.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val id = entry.text("id")?.takeIf { RouterIds.isRouterId(it) } ?: return@mapNotNull null
                val label = entry.text("label") ?: return@mapNotNull null
                val values = (entry["values"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }?.toMap().orEmpty()
                ListedRouter(id, label, entry.text("description"), entry.text("needs"), values, (entry["shared"] as? JsonPrimitive)?.booleanOrNull ?: true)
            }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
