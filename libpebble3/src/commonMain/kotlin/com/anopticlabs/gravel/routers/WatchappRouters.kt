package com.anopticlabs.gravel.routers

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.push.PushTargetApps
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A router a watchapp's JavaScript declared with `Pebble.setRouters`: a place the Notebook's text
 * routing can send text to. [id] names it within the app, [name] and [description] are what the
 * user picks it by, and [params] the request parameters it takes. The limits are the Notebook's
 * for a router of its own (`RoutingConfig`), so a declared router can be saved as one.
 */
@Serializable
data class WatchappRouter(
    val id: String,
    val name: String,
    val description: String? = null,
    val params: List<String> = emptyList(),
) {
    val isWellFormed: Boolean
        get() = ID.matches(id) &&
            isLine(name, MAX_NAME_CHARS) &&
            (description == null || isLine(description, MAX_DESCRIPTION_CHARS)) &&
            params.size <= MAX_PARAMS && params.all(PARAM::matches) && params.toSet().size == params.size

    companion object {
        const val MAX_ROUTERS = 16
        const val MAX_NAME_CHARS = 64
        const val MAX_DESCRIPTION_CHARS = 200
        const val MAX_PARAMS = 16

        private val ID = Regex("[a-z0-9-]{1,32}")
        private val PARAM = Regex("[a-z][a-z0-9_]{0,31}")

        private fun isLine(text: String, max: Int) =
            text.isNotBlank() && text.length <= max && text.trim() == text && text.none(Char::isISOControl)
    }
}

enum class WatchappDelivery {
    /** The app's JavaScript was handed the text. */
    Delivered,

    /** The app is not in the locker. */
    NotInstalled,

    /** No watch is connected to open the app on. */
    NoWatch,

    /** The app was opened, and its JavaScript did not come up in time to take the text. */
    NotTaken,
}

/** One text on its way to a watchapp's JavaScript: [payload] is a JSON object for the router [routerId]. */
class RoutedToWatchapp(val routerId: String, val payload: String) {
    private val taken = CompletableDeferred<Unit>()

    /** True for the one caller that may hand it to the JavaScript; false once the sender gave up on it. */
    fun claim(): Boolean = taken.complete(Unit)

    internal suspend fun awaitClaim() = taken.await()

    /** True when nothing claimed it, and nothing can any more. */
    internal fun giveUp(): Boolean = taken.completeExceptionally(CancellationException("not taken"))
}

interface WatchappRouters {
    /** The routers each watchapp last declared, kept across restarts; an app removed since may still be listed. */
    val watchappRouters: StateFlow<Map<Uuid, List<WatchappRouter>>>

    /**
     * Replaces the routers of [appUuid] with the JSON list [routersJson]; an empty list declares
     * none. False, with nothing changed, for a list that is not well formed.
     */
    suspend fun declareWatchappRouters(appUuid: Uuid, routersJson: String): Boolean

    /**
     * Opens [appUuid] on the connected watches and hands [payload] to its JavaScript as a `route`
     * event for [routerId]; returns once the JavaScript took it or the wait for it ran out.
     */
    suspend fun routeToWatchapp(appUuid: Uuid, routerId: String, payload: String): WatchappDelivery

    /** The texts routed to one watchapp, for its JavaScript session to [RoutedToWatchapp.claim]. */
    fun textsRoutedTo(appUuid: Uuid): Flow<RoutedToWatchapp>
}

/**
 * Keeps what watchapps declare and carries texts to them. A text waits in memory for the app's
 * JavaScript session, as a push does (`WatchappPushManager`), and its sender waits for the
 * session to claim it, so a text counts as delivered only once the JavaScript has it.
 */
class WatchappRouterManager(
    private val settings: Settings,
    private val targetApps: PushTargetApps,
    private val takeTimeout: Duration = 30.seconds,
) : WatchappRouters {
    private val logger = Logger.withTag("WatchappRouterManager")

    // Read on first use: constructing the manager, which the DI graph does at startup, touches no storage.
    private val declared by lazy { MutableStateFlow(load()) }
    override val watchappRouters: StateFlow<Map<Uuid, List<WatchappRouter>>> get() = declared

    private val inboxes = mutableMapOf<Uuid, Channel<RoutedToWatchapp>>()
    private val inboxesLock = Mutex()
    private val declareLock = Mutex()

    private suspend fun inbox(appUuid: Uuid): Channel<RoutedToWatchapp> = inboxesLock.withLock {
        inboxes.getOrPut(appUuid) { Channel(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST) }
    }

    override suspend fun declareWatchappRouters(appUuid: Uuid, routersJson: String): Boolean {
        if (routersJson.length > MAX_DECLARATION_CHARS) return false
        val routers = try {
            json.decodeFromString(ListSerializer(WatchappRouter.serializer()), routersJson)
        } catch (e: SerializationException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        val wellFormed = routers.size <= WatchappRouter.MAX_ROUTERS &&
            routers.all { it.isWellFormed } &&
            routers.map { it.id }.toSet().size == routers.size
        if (!wellFormed) return false
        // Written under the lock, so two apps declaring at once cannot store each other's older map.
        declareLock.withLock {
            val next = if (routers.isEmpty()) declared.value - appUuid else declared.value + (appUuid to routers)
            if (next != declared.value) {
                declared.value = next
                settings.putString(SETTINGS_KEY, json.encodeToString(STORED, next.mapKeys { it.key.toString() }))
            }
        }
        return true
    }

    override suspend fun routeToWatchapp(appUuid: Uuid, routerId: String, payload: String): WatchappDelivery {
        if (!targetApps.isInstalled(appUuid)) return WatchappDelivery.NotInstalled
        val routed = RoutedToWatchapp(routerId, payload)
        inbox(appUuid).send(routed)
        val onWatch = targetApps.launch(appUuid)
        if (onWatch) withTimeoutOrNull(takeTimeout) { routed.awaitClaim() }
        // Giving up fails once the session claimed it, also when the claim came as the wait ran out.
        return when {
            !routed.giveUp() -> WatchappDelivery.Delivered
            onWatch -> WatchappDelivery.NotTaken
            else -> WatchappDelivery.NoWatch
        }
    }

    override fun textsRoutedTo(appUuid: Uuid): Flow<RoutedToWatchapp> = flow { emitAll(inbox(appUuid).receiveAsFlow()) }

    private fun load(): Map<Uuid, List<WatchappRouter>> {
        val stored = settings.getStringOrNull(SETTINGS_KEY) ?: return emptyMap()
        return try {
            json.decodeFromString(STORED, stored).mapKeys { Uuid.parse(it.key) }
        } catch (e: IllegalArgumentException) {
            // Each app declares again when it next runs.
            logger.w { "the stored watchapp routers could not be read: ${e::class.simpleName}" }
            emptyMap()
        }
    }

    companion object {
        private const val SETTINGS_KEY = "gravel.watchapp_routers"
        private const val INBOX_CAPACITY = 8
        private const val MAX_DECLARATION_CHARS = 16 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val STORED = MapSerializer(String.serializer(), ListSerializer(WatchappRouter.serializer()))
    }
}
