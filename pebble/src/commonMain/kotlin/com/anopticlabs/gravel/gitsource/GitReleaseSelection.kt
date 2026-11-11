package com.anopticlabs.gravel.gitsource

import kotlin.time.Instant

/** A release asset over this is not offered; watch app PBWs are a few hundred KiB at most. */
const val MAX_GIT_PBW_BYTES = 2L * 1024 * 1024

data class GitAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class GitRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val publishedAt: Instant,
    val prerelease: Boolean,
    val assets: List<GitAsset>,
)

sealed interface ReleaseSelection {
    data class Found(val release: GitRelease, val asset: GitAsset) : ReleaseSelection
    data object NoRelease : ReleaseSelection
    data class NoAsset(val release: GitRelease) : ReleaseSelection
    data class SeveralAssets(val release: GitRelease, val names: List<String>) : ReleaseSelection
}

/**
 * The newest release by publish time (pre-releases only with [includePrereleases]) and its PBW: the one asset
 * named `*.pbw`, or with [assetPattern] the one PBW whose whole name matches it. Asset entries that cannot be
 * downloaded and checked (not https on the repo's host, an empty or oversized file) are not candidates.
 */
fun selectRelease(
    repo: GitRepo,
    releases: List<GitRelease>,
    includePrereleases: Boolean,
    assetPattern: String?,
): ReleaseSelection {
    val release = releases
        .filter { includePrereleases || !it.prerelease }
        .maxByOrNull { it.publishedAt }
        ?: return ReleaseSelection.NoRelease
    val pattern = assetPattern?.let { runCatching { Regex(it) }.getOrNull() ?: return ReleaseSelection.NoAsset(release) }
    val pbws = release.assets.filter { asset ->
        asset.name.endsWith(".pbw", ignoreCase = true) &&
            (pattern == null || pattern.matches(asset.name)) &&
            asset.size in 1..MAX_GIT_PBW_BYTES &&
            isOnRepoHost(repo, asset.url)
    }
    return when (pbws.size) {
        0 -> ReleaseSelection.NoAsset(release)
        1 -> ReleaseSelection.Found(release, pbws.single())
        else -> ReleaseSelection.SeveralAssets(release, pbws.map { it.name })
    }
}

/**
 * Whether the release [tag] replaces the installed one: another tag, published later. An older release that is newest
 * only because newer ones were deleted or turned into drafts is not offered.
 */
fun isUpdate(installedTag: String, installedPublishedAt: Instant, tag: String, publishedAt: Instant): Boolean =
    tag != installedTag && publishedAt > installedPublishedAt

/**
 * A pattern that matches [assetName] and the same asset in later releases: the name taken literally, except that
 * each run of digits matches any run of digits, so `face-1.2.0-basalt.pbw` also matches `face-1.10.0-basalt.pbw`.
 */
fun assetPatternFor(assetName: String): String = buildString {
    var inDigits = false
    for (c in assetName) {
        if (c.isAsciiDigit()) {
            if (!inDigits) append("[0-9]+")
            inDigits = true
            continue
        }
        inDigits = false
        if (c in REGEX_SPECIAL) append('\\')
        append(c)
    }
}

private fun Char.isAsciiDigit() = this in '0'..'9'

private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}"

/** GitHub serves release files from github.com (it redirects to its storage from there); Forgejo from the instance. */
private fun isOnRepoHost(repo: GitRepo, assetUrl: String): Boolean = httpsOrigin(assetUrl) == repo.baseUrl
