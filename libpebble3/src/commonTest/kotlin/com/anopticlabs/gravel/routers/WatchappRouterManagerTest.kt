package com.anopticlabs.gravel.routers

import com.anopticlabs.gravel.push.PushTargetApps
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class WatchappRouterManagerTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val other = Uuid.parse("00000000-0000-0000-0000-0000000000bb")

    private class FakeTargetApps(var installed: Boolean = true, var watchConnected: Boolean = true) : PushTargetApps {
        val launched = mutableListOf<Uuid>()

        override suspend fun isInstalled(appUuid: Uuid) = installed
        override suspend fun launch(appUuid: Uuid): Boolean {
            launched += appUuid
            return watchConnected
        }
    }

    private fun manager(settings: MapSettings = MapSettings(), apps: FakeTargetApps = FakeTargetApps()) =
        WatchappRouterManager(settings, apps, takeTimeout = 5.seconds)

    @Test
    fun aDeclarationReplacesTheAppsRoutersAndLeavesOtherApps() = runTest {
        val manager = manager()
        assertTrue(manager.declareWatchappRouters(other, """[{"id":"log","name":"Log"}]"""))
        assertTrue(manager.declareWatchappRouters(uuid, """[{"id":"a","name":"A"},{"id":"b","name":"B","description":"Second","params":["station"]}]"""))
        assertTrue(manager.declareWatchappRouters(uuid, """[{"id":"b","name":"B","params":["station"]}]"""))
        assertEquals(
            mapOf(other to listOf(WatchappRouter("log", "Log")), uuid to listOf(WatchappRouter("b", "B", params = listOf("station")))),
            manager.watchappRouters.value,
        )
    }

    @Test
    fun anEmptyDeclarationRemovesTheApp() = runTest {
        val manager = manager()
        manager.declareWatchappRouters(uuid, """[{"id":"a","name":"A"}]""")
        assertTrue(manager.declareWatchappRouters(uuid, "[]"))
        assertEquals(emptyMap(), manager.watchappRouters.value)
    }

    @Test
    fun declarationsSurviveARestart() = runTest {
        val settings = MapSettings()
        manager(settings).declareWatchappRouters(uuid, """[{"id":"a","name":"A"}]""")
        assertEquals(mapOf(uuid to listOf(WatchappRouter("a", "A"))), manager(settings).watchappRouters.value)
    }

    @Test
    fun aDeclarationThatIsNotWellFormedChangesNothing() = runTest {
        val manager = manager()
        manager.declareWatchappRouters(uuid, """[{"id":"a","name":"A"}]""")
        val refused = listOf(
            "not json",
            """{"id":"a","name":"A"}""",
            """[{"id":"Has Spaces","name":"A"}]""",
            """[{"id":"a","name":" padded "}]""",
            """[{"id":"a","name":"${"n".repeat(65)}"}]""",
            """[{"id":"a","name":"A","description":"two\nlines"}]""",
            """[{"id":"a","name":"A","params":["Station"]}]""",
            """[{"id":"a","name":"A","params":["x","x"]}]""",
            """[{"id":"a","name":"A"},{"id":"a","name":"Again"}]""",
            (1..17).joinToString(",", "[", "]") { """{"id":"r$it","name":"R$it"}""" },
        )
        refused.forEach { assertFalse(manager.declareWatchappRouters(uuid, it), it) }
        assertEquals(mapOf(uuid to listOf(WatchappRouter("a", "A"))), manager.watchappRouters.value)
    }

    @Test
    fun aTextIsDeliveredOnceTheSessionClaimsIt() = runTest {
        val apps = FakeTargetApps()
        val manager = manager(apps = apps)
        val result = async { manager.routeToWatchapp(uuid, "a", """{"text":"hi"}""") }
        runCurrent()
        assertEquals(listOf(uuid), apps.launched)
        val routed = manager.textsRoutedTo(uuid).first()
        assertEquals("a" to """{"text":"hi"}""", routed.routerId to routed.payload)
        assertTrue(routed.claim())
        assertEquals(WatchappDelivery.Delivered, result.await())
    }

    @Test
    fun aTextNoSessionClaimsIsNotTakenAndCannotBeClaimedLater() = runTest {
        val manager = manager()
        assertEquals(WatchappDelivery.NotTaken, manager.routeToWatchapp(uuid, "a", "{}"))
        assertFalse(manager.textsRoutedTo(uuid).first().claim())
    }

    @Test
    fun withoutAWatchNothingWaits() = runTest {
        val manager = manager(apps = FakeTargetApps(watchConnected = false))
        assertEquals(WatchappDelivery.NoWatch, manager.routeToWatchapp(uuid, "a", "{}"))
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun anAppOutOfTheLockerIsNotOpened() = runTest {
        val apps = FakeTargetApps(installed = false)
        assertEquals(WatchappDelivery.NotInstalled, manager(apps = apps).routeToWatchapp(uuid, "a", "{}"))
        assertEquals(emptyList(), apps.launched)
    }
}
