package com.anopticlabs.gravel.gitsource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GitReleaseClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val github = GitRepo(GitHost.GITHUB, "https://github.com", "someone", "face")
    private val codeberg = GitRepo(GitHost.FORGEJO, "https://codeberg.org", "someone", "face")

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = "[]", headers: Map<String, String> = emptyMap()) =
        GitReleaseClient(
            HttpClient(MockEngine { request ->
                requests += request
                respond(
                    body,
                    status,
                    headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()),
                )
            }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

    private val githubBody = """
        [
          {"tag_name":"v2","name":"","body":"notes","draft":false,"prerelease":true,"published_at":"2026-02-01T10:00:00Z","author":{},
           "assets":[{"name":"face.pbw","browser_download_url":"https://github.com/someone/face/releases/download/v2/face.pbw",
                      "size":1234,"digest":"sha256:${"A".repeat(64)}","uploader":{}}]},
          {"tag_name":"draft","draft":true,"published_at":null,"assets":[]},
          {"tag_name":"undated","draft":false,"assets":[]}
        ]
    """.trimIndent()

    @Test
    fun readsGithubReleasesWithDigestsAndSkipsDraftsAndUndated() = runTest {
        val result = assertIs<ReleaseFetch.Success>(client(body = githubBody, headers = mapOf(HttpHeaders.ETag to "\"e1\"")).releases(github, null))
        assertEquals("\"e1\"", result.etag)
        val release = result.releases.single()
        assertEquals("v2", release.tag)
        assertEquals("v2", release.name)
        assertEquals("notes", release.notes)
        assertTrue(release.prerelease)
        assertEquals(Instant.parse("2026-02-01T10:00:00Z"), release.publishedAt)
        assertEquals(GitAsset("face.pbw", "https://github.com/someone/face/releases/download/v2/face.pbw", 1234, "a".repeat(64)), release.assets.single())
        val request = requests.single()
        assertEquals("https://api.github.com/repos/someone/face/releases?per_page=10", request.url.toString())
        assertNull(request.headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun readsForgejoReleasesWithoutDigests() = runTest {
        val body = """[{"tag_name":"1.0","published_at":"2026-02-01T10:00:00+01:00",
            "assets":[{"id":3,"name":"face.pbw","size":99,"browser_download_url":"https://codeberg.org/someone/face/releases/download/1.0/face.pbw"}]}]"""
        val result = assertIs<ReleaseFetch.Success>(client(body = body).releases(codeberg, null))
        assertNull(result.releases.single().assets.single().sha256)
        assertEquals(Instant.parse("2026-02-01T09:00:00Z"), result.releases.single().publishedAt)
        assertEquals("https://codeberg.org/api/v1/repos/someone/face/releases?limit=10", requests.single().url.toString())
    }

    @Test
    fun sendsTheEtagAndReadsNotModified() = runTest {
        assertIs<ReleaseFetch.NotModified>(client(HttpStatusCode.NotModified, "").releases(github, "\"e1\""))
        assertEquals("\"e1\"", requests.single().headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun mapsFailures() = runTest {
        assertEquals(GitSourceError.RATE_LIMITED, (client(HttpStatusCode.Forbidden).releases(github, null) as ReleaseFetch.Failure).error)
        assertEquals(GitSourceError.RATE_LIMITED, (client(HttpStatusCode.TooManyRequests).releases(github, null) as ReleaseFetch.Failure).error)
        assertEquals(GitSourceError.NOT_FOUND, (client(HttpStatusCode.NotFound).releases(github, null) as ReleaseFetch.Failure).error)
        assertEquals(GitSourceError.NETWORK, (client(HttpStatusCode.BadGateway).releases(github, null) as ReleaseFetch.Failure).error)
        assertEquals(GitSourceError.MALFORMED, (client(body = "{\"message\":\"x\"}").releases(github, null) as ReleaseFetch.Failure).error)
        val failing = GitReleaseClient(HttpClient(MockEngine { throw kotlinx.io.IOException("offline") }))
        assertEquals(GitSourceError.NETWORK, (failing.releases(github, null) as ReleaseFetch.Failure).error)
    }

    @Test
    fun recognizesAForgeByItsVersionEndpoint() = runTest {
        assertTrue(client(body = """{"version":"11.0.1+gitea-1.22.0"}""").isForge(codeberg))
        assertEquals("https://codeberg.org/api/v1/version", requests.single().url.toString())
        assertFalse(client(body = """{"version":""}""").isForge(codeberg))
        assertFalse(client(HttpStatusCode.NotFound).isForge(codeberg))
        assertFalse(GitReleaseClient(HttpClient(MockEngine { respondError(HttpStatusCode.OK, "<html>") })).isForge(codeberg))
    }
}
