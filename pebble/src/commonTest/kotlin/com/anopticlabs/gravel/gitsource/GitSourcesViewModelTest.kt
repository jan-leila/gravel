package com.anopticlabs.gravel.gitsource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class GitSourcesViewModelTest {
    private val uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val basalt = "basalt-1".encodeToByteArray()
    private val emery = "emery-1".encodeToByteArray()
    private val facts = mapOf(
        "basalt-1" to PbwFacts(uuid, "My Face", "1.0", emptySet()),
        "emery-1" to PbwFacts(uuid, "My Face", "1.0", setOf("location")),
    )

    private val http = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        when {
            url.startsWith("https://api.github.com/") -> respond(
                """[{"tag_name":"v1","published_at":"2026-01-01T00:00:00Z","assets":[
                    {"name":"face-1.0-basalt.pbw","size":${basalt.size},"browser_download_url":"https://github.com/someone/face/releases/download/v1/face-1.0-basalt.pbw"},
                    {"name":"face-1.0-emery.pbw","size":${emery.size},"browser_download_url":"https://github.com/someone/face/releases/download/v1/face-1.0-emery.pbw"}]}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
            url.endsWith("emery.pbw") -> respond(emery, HttpStatusCode.OK)
            else -> respond(basalt, HttpStatusCode.OK)
        }
    }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val dao = FakeDao()
    private val locker = FakeLocker(facts)
    private val staging = Path(SystemTemporaryDirectory, "git-source-vm-test-${Uuid.random()}")
    private val vm by lazy {
        GitSourcesViewModel(GitSourceUpdater(dao, GitReleaseClient(http), http, locker, FakeNotifier(), staging))
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend inline fun <reified T : AddStep> awaitStep(): T =
        withTimeout(10.seconds) { vm.addStep.first { it is T && !it.loading() } as T }

    private fun AddStep.loading() = when (this) {
        is AddStep.Enter -> loading
        is AddStep.ChooseAsset -> loading
        is AddStep.Confirm -> installing
    }

    @Test
    fun severalPbwsAskWhichOneAndThePickBecomesThePattern() = runBlocking {
        vm.startAdd()
        vm.editForm(AddForm(url = "https://github.com/someone/face", autoUpdate = true))
        vm.submitUrl()
        val choose = awaitStep<AddStep.ChooseAsset>()
        assertEquals(listOf("face-1.0-basalt.pbw", "face-1.0-emery.pbw"), choose.preview.names)

        vm.chooseAsset("face-1.0-emery.pbw")
        val confirm = awaitStep<AddStep.Confirm>()
        assertEquals(setOf("location"), confirm.prepared.facts.capabilities)
        assertTrue(locker.sideloads.isEmpty(), "nothing is installed before the user confirms")

        vm.confirmAdd()
        withTimeout(10.seconds) { vm.addStep.first { it == null } }
        assertEquals(listOf("emery-1"), locker.sideloads)
        val row = dao.get(uuid.toString())!!
        assertEquals(assetPatternFor("face-1.0-emery.pbw"), row.assetPattern)
        assertTrue(row.autoUpdate)
        assertFalse(row.includePrereleases)
    }

    @Test
    fun anInvalidAddressStaysOnTheFormWithAMessage() = runBlocking {
        vm.startAdd()
        vm.editForm(AddForm(url = "http://github.com/someone/face"))
        vm.submitUrl()
        val enter = awaitStep<AddStep.Enter>()
        assertEquals(INVALID_URL_MESSAGE, enter.error)
    }

    @Test
    fun cancelingAfterTheDownloadDeletesTheStagedFile() = runBlocking {
        vm.startAdd()
        vm.editForm(AddForm(url = "https://github.com/someone/face"))
        vm.submitUrl()
        awaitStep<AddStep.ChooseAsset>()
        vm.chooseAsset("face-1.0-basalt.pbw")
        awaitStep<AddStep.Confirm>()
        vm.cancelAdd()
        assertNull(vm.addStep.value)
        assertTrue(locker.sideloads.isEmpty())
        locker.stagedPaths.forEach { assertFalse(SystemFileSystem.exists(it), "staged file left behind: $it") }
    }
}
