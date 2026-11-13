package com.anopticlabs.gravel.push

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.database.dao.FakeLockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.locker.PermissionSetting
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class WatchappPushManagerTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val endpoint = PushEndpoint("https://push.example/abc", "pub", "auth")

    private class FakeTransport(var selected: String? = "org.example.distributor") : PushTransport {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()

        override fun distributors() = listOf("org.example.distributor")
        override fun selectedDistributor() = selected
        override fun selectDistributor(packageName: String?) {
            selected = packageName
        }
        override fun register(instance: String) {
            registered += instance
        }
        override fun unregister(instance: String) {
            unregistered += instance
        }
    }

    private class FakeTargetApps(var installed: Boolean = true) : PushTargetApps {
        val launched = mutableListOf<Uuid>()

        override suspend fun isInstalled(appUuid: Uuid) = installed
        override suspend fun launch(appUuid: Uuid) {
            launched += appUuid
        }
    }

    private class Fixture(
        val manager: WatchappPushManager,
        val transport: FakeTransport,
        val targetApps: FakeTargetApps,
        val resolver: WatchappPermissionResolver,
    )

    private fun TestScope.fixture(pushDefault: Boolean = true, transport: FakeTransport? = FakeTransport()): Fixture {
        val config = MutableStateFlow(
            LibPebbleConfig(watchConfig = WatchConfig(watchappDefaultPushAllowed = pushDefault)),
        )
        val resolver = WatchappPermissionResolver(FakeLockerAppPermissionDao(), LibPebbleConfigFlow(config))
        val targetApps = FakeTargetApps()
        val manager = WatchappPushManager(
            transport,
            resolver,
            targetApps,
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            registrationTimeout = 5.seconds,
        )
        return Fixture(manager, transport ?: FakeTransport(), targetApps, resolver)
    }

    @Test
    fun pushIsDeniedByDefault() {
        assertFalse(WatchConfig().watchappDefaultPushAllowed)
    }

    @Test
    fun registrationReturnsTheEndpointTheTransportReports() = runTest {
        val f = fixture()
        val result = async { f.manager.registerWatchappPush(uuid) }
        runCurrent()
        assertEquals(listOf(uuid.toString()), f.transport.registered)
        f.manager.onNewEndpoint(uuid.toString(), endpoint)
        assertEquals(PushRegistration.Registered(endpoint), result.await())
    }

    @Test
    fun anotherAppsEndpointDoesNotAnswerARegistration() = runTest {
        val f = fixture()
        val result = async { f.manager.registerWatchappPush(uuid) }
        runCurrent()
        f.manager.onNewEndpoint(Uuid.random().toString(), PushEndpoint("https://push.example/other", null, null))
        runCurrent()
        assertFalse(result.isCompleted)
        f.manager.onNewEndpoint(uuid.toString(), endpoint)
        assertEquals(PushRegistration.Registered(endpoint), result.await())
    }

    @Test
    fun registrationWithoutTheGrantNeverReachesTheTransport() = runTest {
        val f = fixture(pushDefault = false)
        assertEquals(PushRegistration.Failed(PushFailure.PermissionDenied), f.manager.registerWatchappPush(uuid))
        assertTrue(f.transport.registered.isEmpty())
    }

    @Test
    fun registrationFailsWithoutADistributor() = runTest {
        val f = fixture(transport = FakeTransport(selected = null))
        assertEquals(PushRegistration.Failed(PushFailure.NoDistributor), f.manager.registerWatchappPush(uuid))
    }

    @Test
    fun registrationFailsWhereThereIsNoTransport() = runTest {
        val f = fixture(transport = null)
        assertEquals(PushRegistration.Failed(PushFailure.Unsupported), f.manager.registerWatchappPush(uuid))
    }

    @Test
    fun registrationReportsADistributorsRefusalAndItsSilence() = runTest {
        val f = fixture()
        val refused = async { f.manager.registerWatchappPush(uuid) }
        runCurrent()
        f.manager.onRegistrationFailed(uuid.toString())
        assertEquals(PushRegistration.Failed(PushFailure.RegistrationFailed), refused.await())

        val unanswered = async { f.manager.registerWatchappPush(uuid) }
        advanceTimeBy(6.seconds)
        assertEquals(PushRegistration.Failed(PushFailure.Timeout), unanswered.await())
    }

    @Test
    fun aMessageOpensTheAppAndWaitsForItsJavaScript() = runTest {
        val f = fixture()
        f.manager.onMessage(uuid.toString(), "hello".encodeToByteArray(), decrypted = true)
        runCurrent()
        assertEquals(listOf(uuid), f.targetApps.launched)
        assertEquals(PushMessage("hello", encrypted = true), f.manager.watchappPushMessages(uuid).first())
    }

    @Test
    fun aMessageWithoutABodyStillOpensTheApp() = runTest {
        val f = fixture()
        f.manager.onMessage(uuid.toString(), ByteArray(0), decrypted = false)
        runCurrent()
        assertEquals(listOf(uuid), f.targetApps.launched)
        assertEquals(PushMessage("", encrypted = false), f.manager.watchappPushMessages(uuid).first())
    }

    @Test
    fun aMessageForAnAppWithoutTheGrantIsDroppedAndUnregistered() = runTest {
        val f = fixture()
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Push, PermissionSetting.Deny)
        f.manager.onMessage(uuid.toString(), "hello".encodeToByteArray(), decrypted = true)
        runCurrent()
        assertTrue(f.targetApps.launched.isEmpty())
        assertEquals(listOf(uuid.toString()), f.transport.unregistered)
    }

    @Test
    fun aMessageForARemovedAppIsDroppedAndUnregistered() = runTest {
        val f = fixture()
        f.targetApps.installed = false
        f.manager.onMessage(uuid.toString(), "hello".encodeToByteArray(), decrypted = true)
        runCurrent()
        assertTrue(f.targetApps.launched.isEmpty())
        assertEquals(listOf(uuid.toString()), f.transport.unregistered)
    }

    @Test
    fun aMessageQueuedBeforeARevocationIsNotDelivered() = runTest {
        val f = fixture()
        f.manager.onMessage(uuid.toString(), "before".encodeToByteArray(), decrypted = true)
        runCurrent()
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Push, PermissionSetting.Deny)
        val delivered = async { f.manager.watchappPushMessages(uuid).first() }
        runCurrent()
        assertFalse(delivered.isCompleted)
        delivered.cancel()
    }

    @Test
    fun aMessageToGravelsOwnRegistrationOpensNothing() = runTest {
        val f = fixture()
        f.manager.onMessage(WatchappPushManager.APP_INSTANCE, "hello".encodeToByteArray(), decrypted = true)
        runCurrent()
        assertTrue(f.targetApps.launched.isEmpty())
        assertTrue(f.transport.unregistered.isEmpty())
    }

    @Test
    fun choosingADistributorRegistersGravelItself() = runTest {
        val f = fixture(transport = FakeTransport(selected = null))
        f.manager.selectPushDistributor("org.example.distributor")
        assertEquals(listOf(WatchappPushManager.APP_INSTANCE), f.transport.registered)
        assertEquals("org.example.distributor", f.manager.pushState.value.selectedDistributor)
        assertFalse(f.manager.pushState.value.appRegistered)

        f.manager.onNewEndpoint(WatchappPushManager.APP_INSTANCE, endpoint)
        assertTrue(f.manager.pushState.value.appRegistered)

        f.manager.selectPushDistributor(null)
        assertEquals(PushState(listOf("org.example.distributor"), null, false), f.manager.pushState.value)
    }
}
