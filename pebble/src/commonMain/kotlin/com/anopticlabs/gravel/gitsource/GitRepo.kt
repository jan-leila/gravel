package com.anopticlabs.gravel.gitsource

import io.ktor.http.URLProtocol
import io.ktor.http.Url

enum class GitHost { GITHUB, FORGEJO }

/** A repository on a git host: [baseUrl] is the web origin (`https://host[:port]`), no trailing slash. */
data class GitRepo(val host: GitHost, val baseUrl: String, val owner: String, val repo: String) {
    val webUrl: String get() = "$baseUrl/$owner/$repo"

    /** The releases list the host's API serves; GitHub's API is on its own origin, Forgejo's (and Gitea's) under the instance. */
    val releasesApiUrl: String
        get() = when (host) {
            GitHost.GITHUB -> "https://api.github.com/repos/$owner/$repo/releases"
            GitHost.FORGEJO -> "$baseUrl/api/v1/repos/$owner/$repo/releases"
        }

    val displayName: String get() = "${baseUrl.removePrefix("https://")}/$owner/$repo"
}

private val NAME = Regex("[A-Za-z0-9._-]{1,100}")

/**
 * Reads a repository address as typed or pasted: `https://host[:port]/owner/repo`, optionally ending in `.git`
 * or followed by more path (a releases page, say). Only github.com counts as GitHub; any other host is assumed to
 * be Forgejo or Gitea, which the caller confirms against the instance. Instances served under a subpath are not
 * recognized: the first two path segments are always the owner and the repo. Null for anything else, including
 * http, user info and names that are not plain path segments.
 */
fun parseGitRepoUrl(input: String): GitRepo? {
    val trimmed = input.trim()
    val origin = httpsOrigin(trimmed) ?: return null
    val url = Url(trimmed)
    if (url.user != null || url.password != null) return null
    val segments = url.segments.filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    val owner = segments[0]
    val repo = segments[1].removeSuffix(".git")
    if (!NAME.matches(owner) || !NAME.matches(repo) || owner.all { it == '.' } || repo.all { it == '.' }) return null
    val isGithub = origin == "https://github.com" || origin == "https://www.github.com"
    return GitRepo(
        host = if (isGithub) GitHost.GITHUB else GitHost.FORGEJO,
        baseUrl = if (isGithub) "https://github.com" else origin,
        owner = owner,
        repo = repo,
    )
}

/**
 * `https://host[:port]` of [address] with the host lowercased and the default port dropped, or null when it is not
 * an https address. The scheme is checked on the text too: ktor's parser reads a scheme-less address as http.
 */
internal fun httpsOrigin(address: String): String? {
    if (!address.startsWith("https://", ignoreCase = true)) return null
    val url = runCatching { Url(address) }.getOrNull() ?: return null
    if (url.protocol != URLProtocol.HTTPS || url.host.isEmpty()) return null
    val port = if (url.specifiedPort == 0 || url.specifiedPort == 443) "" else ":${url.specifiedPort}"
    return "https://${url.host.lowercase()}$port"
}
