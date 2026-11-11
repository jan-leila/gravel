package com.anopticlabs.gravel.gitsource

import co.touchlab.kermit.Logger
import coredevices.pebble.firmware.normalizeSha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Why a check or install did not finish. [transient] ones are retried at the next background check; the rest wait for the user. */
enum class GitSourceError(val transient: Boolean) {
    NETWORK(true),
    RATE_LIMITED(true),
    NOT_FOUND(false),
    NOT_A_FORGE(false),
    MALFORMED(false),
    NO_RELEASE(false),
    NO_ASSET(false),
    SEVERAL_ASSETS(false),
    CHECK_FAILED(false),
    WRONG_APP(false),
    INSTALL_FAILED(false),
}

sealed interface ReleaseFetch {
    data class Success(val releases: List<GitRelease>, val etag: String?) : ReleaseFetch
    data object NotModified : ReleaseFetch
    data class Failure(val error: GitSourceError) : ReleaseFetch
}

/**
 * Reads releases from GitHub's REST API and from Forgejo's (Gitea's API, which Forgejo keeps), anonymously. The two
 * release objects share the fields read here. Requests go only to the repository's own host (for GitHub, its API
 * origin), and carry nothing about the phone or watch.
 */
class GitReleaseClient(private val httpClient: HttpClient) {
    private val logger = Logger.withTag("GitReleaseClient")

    /**
     * The newest [PAGE_SIZE] releases, newest first as both hosts list them. With [etag], an unchanged list answers
     * [ReleaseFetch.NotModified] without a body.
     */
    suspend fun releases(repo: GitRepo, etag: String?): ReleaseFetch {
        val response = request(repo.releasesApiUrl) {
            when (repo.host) {
                GitHost.GITHUB -> parameter("per_page", PAGE_SIZE)
                GitHost.FORGEJO -> parameter("limit", PAGE_SIZE)
            }
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        } ?: return ReleaseFetch.Failure(GitSourceError.NETWORK)
        when {
            response.status == HttpStatusCode.NotModified -> return ReleaseFetch.NotModified
            // GitHub answers an exhausted anonymous quota with 403 or 429 (REST API docs, "Rate limits").
            response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.TooManyRequests ->
                return ReleaseFetch.Failure(GitSourceError.RATE_LIMITED)
            response.status == HttpStatusCode.NotFound -> return ReleaseFetch.Failure(GitSourceError.NOT_FOUND)
            !response.status.isSuccess() -> return ReleaseFetch.Failure(GitSourceError.NETWORK)
        }
        val dtos = try {
            response.body<List<ReleaseDto>>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Unreadable release list from ${repo.displayName}" }
            return ReleaseFetch.Failure(GitSourceError.MALFORMED)
        }
        return ReleaseFetch.Success(dtos.mapNotNull { it.toRelease() }, response.headers[HttpHeaders.ETag])
    }

    /** Whether the instance at [repo]'s origin answers Forgejo's (and Gitea's) version endpoint. */
    suspend fun isForge(repo: GitRepo): Boolean {
        val response = request("${repo.baseUrl}/api/v1/version") {} ?: return false
        if (!response.status.isSuccess()) return false
        return try {
            response.body<ForgeVersionDto>().version.isNotBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun request(url: String, configure: HttpRequestBuilder.() -> Unit): HttpResponse? =
        try {
            httpClient.get(url) {
                header(HttpHeaders.Accept, "application/json")
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Request to $url failed" }
            null
        }

    companion object {
        const val PAGE_SIZE = 10
    }
}

@Serializable
private data class ForgeVersionDto(val version: String = "")

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    // GitHub only; assets uploaded before GitHub began computing digests have none.
    val digest: String? = null,
)

/** Null for drafts and for releases without a readable publish time, which cannot be ordered. */
private fun ReleaseDto.toRelease(): GitRelease? {
    if (draft) return null
    val published = publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return GitRelease(
        tag = tagName,
        name = name?.takeIf { it.isNotBlank() } ?: tagName,
        notes = body.orEmpty(),
        publishedAt = published,
        prerelease = prerelease,
        assets = assets.map { GitAsset(it.name, it.browserDownloadUrl, it.size, normalizeSha256Hex(it.digest)) },
    )
}
