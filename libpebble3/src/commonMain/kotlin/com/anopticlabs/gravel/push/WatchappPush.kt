package com.anopticlabs.gravel.push

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The platform's push client: the UnifiedPush connector on Android. Gravel is one client of the
 * distributor app, and each registration under it is an instance with its own endpoint.
 */
interface PushTransport {
    /** Package names of the installed distributor apps. */
    fun distributors(): List<String>

    fun selectedDistributor(): String?

    /**
     * `null` is the user turning push off: it drops the distributor and every registration made
     * through it, and is remembered ([optedOut]) until a distributor is selected again.
     */
    fun selectDistributor(packageName: String?)

    /** The user chose Off, so [WatchappPushManager] must not pick a distributor itself. */
    fun optedOut(): Boolean

    /** Answered later through [PushTransportEvents], also when the instance is already registered. */
    fun register(instance: String)

    fun unregister(instance: String)
}

/** What the platform's push receiver reports; implemented by [WatchappPushManager]. */
interface PushTransportEvents {
    fun onNewEndpoint(instance: String, endpoint: PushEndpoint)
    fun onRegistrationFailed(instance: String)
    fun onUnregistered(instance: String)
    fun onMessage(instance: String, content: ByteArray, decrypted: Boolean)
}

/** [p256dh] and [auth] are the Web Push keys a sender encrypts with (RFC 8291). */
data class PushEndpoint(val url: String, val p256dh: String?, val auth: String?)

/**
 * [encrypted] is false for a body the sender posted in the clear, which the push server could read.
 * [channel] is the named channel the message arrived on; null for the app's default channel.
 */
data class PushMessage(val body: String, val encrypted: Boolean, val channel: String? = null)

/** [code] is what the watchapp's JavaScript receives. */
enum class PushFailure(val code: String) {
    PermissionDenied("permission_denied"),
    NoDistributor("no_distributor"),
    RegistrationFailed("registration_failed"),
    Timeout("timeout"),
    Unsupported("unsupported"),
    InvalidChannel("invalid_channel"),
}

sealed interface PushRegistration {
    data class Registered(val endpoint: PushEndpoint) : PushRegistration
    data class Failed(val reason: PushFailure) : PushRegistration
}

data class PushState(
    val distributors: List<String> = emptyList(),
    val selectedDistributor: String? = null,
    /** Gravel's own registration answered since the distributor was chosen or the state refreshed. */
    val appRegistered: Boolean = false,
)

interface WatchappPush {
    val pushState: StateFlow<PushState>

    /** Re-reads the installed distributors and re-checks Gravel's own registration. */
    fun refreshPushState()

    fun selectPushDistributor(packageName: String?)

    /** [channel] names one of the app's channels, each with its own endpoint; null is the default channel. */
    suspend fun registerWatchappPush(appUuid: Uuid, channel: String? = null): PushRegistration

    /** Messages for one watchapp, each delivered to a single collector. */
    fun watchappPushMessages(appUuid: Uuid): Flow<PushMessage>
}

/**
 * A watchapp's channels. Each is its own registration with the distributor, so an arriving message
 * names its channel in the instance without Gravel reading the body. The default channel's instance
 * is the bare app UUID, which is what registrations made before channels existed already use.
 */
object PushChannel {
    private val NAME = Regex("[a-z0-9-]{1,32}")
    private const val SEPARATOR = '/'

    fun isValidName(name: String) = NAME.matches(name)

    fun instance(appUuid: Uuid, channel: String?): String =
        if (channel == null) appUuid.toString() else "$appUuid$SEPARATOR$channel"

    /** The app and channel an instance names; null for an instance that is neither. */
    fun parse(instance: String): Pair<Uuid, String?>? {
        val app = instance.substringBefore(SEPARATOR)
        val channel = if (SEPARATOR in instance) instance.substringAfter(SEPARATOR) else null
        if (channel != null && !isValidName(channel)) return null
        val uuid = try {
            Uuid.parse(app)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return uuid to channel
    }
}

/** The watch side of a push: whether its target still exists, and opening it. */
interface PushTargetApps {
    suspend fun isInstalled(appUuid: Uuid): Boolean
    /** False when no watch is connected to open it on. */
    suspend fun launch(appUuid: Uuid): Boolean
}

class LockerPushTargetApps(
    private val lockerEntryDao: LockerEntryRealDao,
    private val watches: WatchManager,
) : PushTargetApps {
    override suspend fun isInstalled(appUuid: Uuid): Boolean = lockerEntryDao.getEntry(appUuid) != null

    override suspend fun launch(appUuid: Uuid): Boolean {
        val connected = watches.watches.value.filterIsInstance<ConnectedPebbleDevice>()
        connected.forEach { it.launchApp(appUuid) }
        return connected.isNotEmpty()
    }
}

/**
 * Routes pushes to watchapps. A watchapp's registration is the instance named by its UUID, so an
 * incoming message names its target; Gravel's own registration ([APP_INSTANCE]) only shows that
 * the distributor answers.
 */
class WatchappPushManager(
    private val transport: PushTransport?,
    private val permissions: WatchappPermissionResolver,
    private val targetApps: PushTargetApps,
    private val scope: LibPebbleCoroutineScope,
    private val registrationTimeout: Duration = 15.seconds,
) : WatchappPush, PushTransportEvents {
    private val logger = Logger.withTag("WatchappPushManager")

    private val _pushState = MutableStateFlow(PushState())
    override val pushState: StateFlow<PushState> = _pushState.asStateFlow()

    private class RegistrationResult(val instance: String, val endpoint: PushEndpoint?)

    private val registrationResults = MutableSharedFlow<RegistrationResult>(extraBufferCapacity = 16)

    private val inboxes = mutableMapOf<Uuid, Channel<PushMessage>>()
    private val inboxesLock = Mutex()

    // Held in memory until the app's JavaScript is running; the oldest goes first when a sender
    // outpaces that.
    private suspend fun inbox(appUuid: Uuid): Channel<PushMessage> = inboxesLock.withLock {
        inboxes.getOrPut(appUuid) { Channel(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST) }
    }

    override fun refreshPushState() {
        val transport = transport ?: return
        val selected = selectedOrAutoSelected(transport)
        _pushState.update {
            PushState(transport.distributors(), selected, it.appRegistered && selected != null)
        }
        if (selected != null) transport.register(APP_INSTANCE)
    }

    // With exactly one distributor installed there is nothing to choose, so it is used without the
    // user visiting the settings. With several, picking one is left to the user.
    private fun selectedOrAutoSelected(transport: PushTransport): String? {
        transport.selectedDistributor()?.let { return it }
        if (transport.optedOut()) return null
        val only = transport.distributors().singleOrNull() ?: return null
        transport.selectDistributor(only)
        return only
    }

    override fun selectPushDistributor(packageName: String?) {
        val transport = transport ?: return
        transport.selectDistributor(packageName)
        _pushState.update { it.copy(appRegistered = false) }
        refreshPushState()
    }

    override suspend fun registerWatchappPush(appUuid: Uuid, channel: String?): PushRegistration {
        if (transport == null) return PushRegistration.Failed(PushFailure.Unsupported)
        if (!permissions.isWatchappPermissionGranted(appUuid, LockerAppPermissionType.Push)) {
            return PushRegistration.Failed(PushFailure.PermissionDenied)
        }
        if (selectedOrAutoSelected(transport) == null) return PushRegistration.Failed(PushFailure.NoDistributor)
        if (channel != null && !PushChannel.isValidName(channel)) return PushRegistration.Failed(PushFailure.InvalidChannel)
        val instance = PushChannel.instance(appUuid, channel)
        val result = withTimeoutOrNull(registrationTimeout) {
            // Subscribed before the request goes out, so an immediate answer is not missed.
            registrationResults
                .onSubscription { transport.register(instance) }
                .first { it.instance == instance }
        } ?: return PushRegistration.Failed(PushFailure.Timeout)
        return result.endpoint?.let { PushRegistration.Registered(it) }
            ?: PushRegistration.Failed(PushFailure.RegistrationFailed)
    }

    override fun watchappPushMessages(appUuid: Uuid): Flow<PushMessage> =
        flow { emitAll(inbox(appUuid).receiveAsFlow()) }
            // A message can wait in the inbox across a revocation.
            .filter { permissions.isWatchappPermissionGranted(appUuid, LockerAppPermissionType.Push) }

    override fun onNewEndpoint(instance: String, endpoint: PushEndpoint) {
        if (instance == APP_INSTANCE) _pushState.update { it.copy(appRegistered = true) }
        registrationResults.tryEmit(RegistrationResult(instance, endpoint))
    }

    override fun onRegistrationFailed(instance: String) {
        if (instance == APP_INSTANCE) _pushState.update { it.copy(appRegistered = false) }
        registrationResults.tryEmit(RegistrationResult(instance, null))
    }

    override fun onUnregistered(instance: String) {
        if (instance == APP_INSTANCE) _pushState.update { it.copy(appRegistered = false) }
    }

    override fun onMessage(instance: String, content: ByteArray, decrypted: Boolean) {
        val (appUuid, channel) = PushChannel.parse(instance) ?: return
        scope.launch {
            val allowed = permissions.isWatchappPermissionGranted(appUuid, LockerAppPermissionType.Push)
            if (!allowed || !targetApps.isInstalled(appUuid)) {
                // The endpoint outlives a revoked grant or a removed app until it is dropped here.
                logger.i { "Push for $appUuid refused (allowed=$allowed); unregistering" }
                transport?.unregister(instance)
                return@launch
            }
            inbox(appUuid).send(PushMessage(content.decodeToString(), encrypted = decrypted, channel = channel))
            targetApps.launch(appUuid)
        }
    }

    companion object {
        const val APP_INSTANCE = "gravel"
        private const val INBOX_CAPACITY = 8
    }
}
