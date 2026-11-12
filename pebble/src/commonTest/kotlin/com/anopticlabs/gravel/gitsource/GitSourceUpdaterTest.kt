package com.anopticlabs.gravel.gitsource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GitSourceUpdaterTest {
    private val appUuid = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val otherUuid = Uuid.parse("99999999-2222-3333-4444-555555555555")

    /** Each fake PBW's bytes are its key into [facts], which stands in for reading `appinfo.json`. */
    private val facts = mutableMapOf<String, PbwFacts>()

    private fun pbw(name: String, uuid: Uuid = appUuid, capabilities: Set<String> = emptySet()): ByteArray {
        facts[name] = PbwFacts(uuid, "My Face", name, capabilities)
        return name.encodeToByteArray()
    }

    private class Served(val tag: String, val day: Int, val file: String, val bytes: ByteArray, val digest: String?, val listedSize: Long = bytes.size.toLong())

    private var served = listOf<Served>()
    private var etagServed: String? = "\"e1\""
    private val requests = mutableListOf<String>()
    private val ifNoneMatch = mutableListOf<String?>()

    private fun releasesJson() = served.joinToString(",", "[", "]") { r ->
        val digest = r.digest?.let { ",\"digest\":\"sha256:$it\"" }.orEmpty()
        """{"tag_name":"${r.tag}","body":"notes ${r.tag}","published_at":"2026-01-${r.day.toString().padStart(2, '0')}T00:00:00Z",
            "assets":[{"name":"${r.file}","size":${r.listedSize},"browser_download_url":"https://github.com/someone/face/releases/download/${r.tag}/${r.file}"$digest}]}"""
    }

    private val http = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        requests += url
        when {
            url.startsWith("https://api.github.com/repos/someone/face/releases") -> {
                ifNoneMatch += request.headers[HttpHeaders.IfNoneMatch]
                val etag = etagServed
                if (etag != null && request.headers[HttpHeaders.IfNoneMatch] == etag) {
                    respond("", HttpStatusCode.NotModified)
                } else {
                    val headers = listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), etag?.let { HttpHeaders.ETag to listOf(it) })
                    respond(releasesJson(), HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
                }
            }
            else -> {
                val r = served.first { url.endsWith("/${it.tag}/${it.file}") }
                respond(r.bytes, HttpStatusCode.OK)
            }
        }
    }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private var now = Instant.parse("2026-02-01T00:00:00Z")
    private val clock = object : Clock { override fun now() = now }
    private val dao = FakeDao()
    private val locker = FakeLocker(facts)
    private val notifier = FakeNotifier()
    private val staging = Path(SystemTemporaryDirectory, "git-source-test-${Uuid.random()}")
    private val updater = GitSourceUpdater(dao, GitReleaseClient(http), http, locker, notifier, staging, clock)

    private suspend fun addFirstRelease(capabilities: Set<String> = emptySet(), autoUpdate: Boolean = false) {
        val v1 = pbw("v1", capabilities = capabilities)
        served = listOf(Served("v1", 1, "face.pbw", v1, sha(v1)))
        val ready = assertIs<AddPreview.Ready>(updater.preview("https://github.com/someone/face", false, null))
        val prepareResult = updater.prepare(ready)
        val prepared = assertIs<PrepareResult.Ready>(prepareResult, prepareResult.toString()).prepared
        assertEquals(appUuid, prepared.facts.uuid)
        assertEquals(false, prepared.alreadyInLocker)
        assertEquals(InstallResult.Installed, updater.completeAdd(prepared, includePrereleases = false, autoUpdate = autoUpdate))
    }

    private suspend fun publishV2(bytes: ByteArray = pbw("v2"), digest: String? = sha(bytes), listedSize: Long = bytes.size.toLong()) {
        served = listOf(Served("v2", 5, "face.pbw", bytes, digest, listedSize)) + served
        etagServed = "\"e2\""
    }

    private suspend fun status() = updater.source(appUuid).first()!!.status

    @Test
    fun addInstallsTheLatestReleaseAndRecordsIt() = runBlocking {
        addFirstRelease(capabilities = setOf("location"))
        val row = dao.get(appUuid.toString())!!
        assertEquals("v1", row.installedTag)
        assertEquals("location", row.installedCapabilities)
        assertEquals("My Face", row.appTitle)
        assertEquals(listOf("v1"), locker.sideloads)
        assertEquals(GitSourceStatus.UP_TO_DATE, status())
        locker.stagedPaths.forEach { assertTrue(!SystemFileSystem.exists(it), "staged file left behind: $it") }
    }

    @Test
    fun aNewerReleaseIsOfferedAndInstalled() = runBlocking {
        addFirstRelease()
        publishV2()
        assertNull(updater.check(appUuid))
        assertNull(ifNoneMatch.last())
        assertEquals(GitSourceStatus.UPDATE_AVAILABLE, status())
        assertEquals(InstallResult.Installed, updater.install(appUuid))
        assertEquals("v2", dao.get(appUuid.toString())!!.installedTag)
        assertEquals(GitSourceStatus.UP_TO_DATE, status())
        assertEquals(listOf(appUuid.toString()), notifier.canceled)
    }

    @Test
    fun anUnchangedListKeepsWhatTheLastCheckFound() = runBlocking {
        addFirstRelease()
        publishV2()
        updater.check(appUuid)
        updater.check(appUuid)
        assertEquals("\"e2\"", ifNoneMatch.last())
        assertEquals("v2", dao.get(appUuid.toString())!!.availableTag)
    }

    @Test
    fun aReleaseOfAnotherAppIsRefused() = runBlocking {
        addFirstRelease()
        publishV2(pbw("v2-other", uuid = otherUuid))
        updater.check(appUuid)
        assertEquals(InstallResult.Failed(GitSourceError.WRONG_APP), updater.install(appUuid))
        assertEquals(listOf("v1"), locker.sideloads)
        assertEquals("v1", dao.get(appUuid.toString())!!.installedTag)
    }

    @Test
    fun aDownloadThatIsNotWhatTheApiListedIsRefused() = runBlocking {
        addFirstRelease()
        publishV2(digest = "0".repeat(64))
        updater.check(appUuid)
        assertEquals(InstallResult.Failed(GitSourceError.CHECK_FAILED), updater.install(appUuid))
        val v3 = pbw("v3")
        served = listOf(Served("v3", 6, "face.pbw", v3, digest = null, listedSize = v3.size + 1L)) + served
        etagServed = "\"e3\""
        updater.check(appUuid)
        assertEquals(InstallResult.Failed(GitSourceError.CHECK_FAILED), updater.install(appUuid))
        assertEquals(listOf("v1"), locker.sideloads)
    }

    @Test
    fun aReleaseWithoutDigestIsInstalledWhenItsSizeMatches() = runBlocking {
        addFirstRelease()
        publishV2(digest = null)
        updater.check(appUuid)
        assertEquals(InstallResult.Installed, updater.install(appUuid))
    }

    @Test
    fun newCapabilitiesWaitForApprovalOfExactlyThose() = runBlocking {
        addFirstRelease(capabilities = setOf("configurable"))
        publishV2(pbw("v2", capabilities = setOf("configurable", "location", PKJS_CAPABILITY)))
        updater.check(appUuid)
        assertEquals(InstallResult.NeedsApproval(setOf("location", PKJS_CAPABILITY)), updater.install(appUuid))
        assertEquals(GitSourceStatus.NEEDS_APPROVAL, status())
        assertEquals(setOf("location", PKJS_CAPABILITY), updater.source(appUuid).first()!!.pendingCapabilities)
        assertIs<InstallResult.NeedsApproval>(updater.install(appUuid, setOf("location")))
        assertEquals(InstallResult.Installed, updater.install(appUuid, setOf("location", PKJS_CAPABILITY)))
        assertEquals(GitSourceStatus.UP_TO_DATE, status())
        assertEquals(setOf("configurable", "location", PKJS_CAPABILITY), dao.get(appUuid.toString())!!.installedCapabilities.toCapabilities())
    }

    @Test
    fun backgroundAutoUpdatesOrNotifiesOncePerRelease() = runBlocking {
        addFirstRelease(autoUpdate = true)
        publishV2()
        now += 7.hours
        updater.backgroundCheck()
        assertEquals("v2", dao.get(appUuid.toString())!!.installedTag)
        assertTrue(notifier.posted.isEmpty())

        updater.setOptions(appUuid, includePrereleases = false, autoUpdate = false, assetPattern = null)
        val v3 = pbw("v3")
        served = listOf(Served("v3", 6, "face.pbw", v3, sha(v3))) + served
        etagServed = "\"e3\""
        now += 7.hours
        updater.backgroundCheck()
        now += 7.hours
        updater.backgroundCheck()
        assertEquals(listOf("v3" to false), notifier.posted)
        assertEquals("v2", dao.get(appUuid.toString())!!.installedTag)
    }

    @Test
    fun backgroundNeverInstallsOverACopyReplacedOutsideGitSources() = runBlocking {
        addFirstRelease(autoUpdate = true)
        locker.installed.value += appUuid to sha("hand-sideloaded".encodeToByteArray())
        publishV2()
        now += 7.hours
        updater.backgroundCheck()
        assertEquals(GitSourceStatus.REPLACED, status())
        assertEquals(listOf("v1"), locker.sideloads)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun backgroundAsksBeforeAutoInstallingNewCapabilities() = runBlocking {
        addFirstRelease(autoUpdate = true)
        publishV2(pbw("v2", capabilities = setOf("location")))
        now += 7.hours
        updater.backgroundCheck()
        assertEquals(listOf("v2" to true), notifier.posted)
        assertEquals(listOf("v1"), locker.sideloads)
    }

    @Test
    fun backgroundChecksAreSpacedUnlessTheLastOneFailedTransiently() = runBlocking {
        addFirstRelease()
        val listRequests = { requests.count { it.startsWith("https://api.github.com/") } }
        val before = listRequests()
        now += 30.minutes
        updater.backgroundCheck()
        assertEquals(before, listRequests())

        dao.upsert(dao.get(appUuid.toString())!!.copy(lastError = GitSourceError.RATE_LIMITED.name))
        updater.backgroundCheck()
        assertEquals(before + 1, listRequests())
        assertNull(dao.get(appUuid.toString())!!.lastError)
    }

    @Test
    fun aRemovedAppIsNotInstalledAgainInTheBackground() = runBlocking {
        addFirstRelease(autoUpdate = true)
        locker.installed.value = emptyMap()
        publishV2()
        now += 7.hours
        updater.backgroundCheck()
        assertEquals(GitSourceStatus.NOT_INSTALLED, status())
        assertEquals(listOf("v1"), locker.sideloads)
    }

    @Test
    fun forgetStopsFollowingWithoutTouchingTheLocker() = runBlocking {
        addFirstRelease()
        updater.forget(appUuid)
        assertNull(dao.get(appUuid.toString()))
        assertTrue(appUuid in locker.installed.value)
    }

    @Test
    fun aForgeThatDoesNotAnswerIsRefusedAtPreview() = runBlocking {
        val noForge = GitSourceUpdater(dao, GitReleaseClient(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })), http, locker, notifier, staging, clock)
        assertEquals(AddPreview.Failed(GitSourceError.NOT_A_FORGE), noForge.preview("https://example.org/a/b", false, null))
        assertEquals(AddPreview.InvalidUrl, noForge.preview("http://example.org/a/b", false, null))
    }
}
