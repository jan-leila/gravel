package com.anopticlabs.gravel.gitsource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class GitReleaseSelectionTest {
    private val repo = GitRepo(GitHost.GITHUB, "https://github.com", "someone", "face")

    private fun asset(name: String, size: Long = 1000, url: String = "https://github.com/someone/face/releases/download/x/$name") =
        GitAsset(name, url, size, null)

    private fun release(tag: String, day: Int, vararg assets: GitAsset, prerelease: Boolean = false) =
        GitRelease(tag, tag, "", Instant.parse("2026-01-${day.toString().padStart(2, '0')}T00:00:00Z"), prerelease, assets.toList())

    @Test
    fun picksTheNewestReleaseByPublishTimeAndItsOnlyPbw() {
        val old = release("v2", 1, asset("face.pbw"))
        val new = release("v1.9", 5, asset("face.pbw"), asset("README.md"))
        val found = assertIs<ReleaseSelection.Found>(selectRelease(repo, listOf(old, new), false, null))
        assertEquals("v1.9", found.release.tag)
        assertEquals("face.pbw", found.asset.name)
    }

    @Test
    fun preReleasesOnlyWhenAskedFor() {
        val stable = release("v1", 1, asset("face.pbw"))
        val beta = release("v2-beta", 5, asset("face.pbw"), prerelease = true)
        assertEquals("v1", (selectRelease(repo, listOf(stable, beta), false, null) as ReleaseSelection.Found).release.tag)
        assertEquals("v2-beta", (selectRelease(repo, listOf(stable, beta), true, null) as ReleaseSelection.Found).release.tag)
        assertIs<ReleaseSelection.NoRelease>(selectRelease(repo, listOf(beta), false, null))
        assertIs<ReleaseSelection.NoRelease>(selectRelease(repo, emptyList(), true, null))
    }

    @Test
    fun severalPbwsNeedAPatternAndThePatternMustMatchOne() {
        val r = release("v1", 1, asset("face-1.0-basalt.pbw"), asset("face-1.0-emery.pbw"))
        val several = assertIs<ReleaseSelection.SeveralAssets>(selectRelease(repo, listOf(r), false, null))
        assertEquals(listOf("face-1.0-basalt.pbw", "face-1.0-emery.pbw"), several.names)
        val found = assertIs<ReleaseSelection.Found>(selectRelease(repo, listOf(r), false, assetPatternFor("face-0.9-emery.pbw")))
        assertEquals("face-1.0-emery.pbw", found.asset.name)
        assertIs<ReleaseSelection.NoAsset>(selectRelease(repo, listOf(r), false, "nothing\\.pbw"))
        assertIs<ReleaseSelection.NoAsset>(selectRelease(repo, listOf(r), false, "(unclosed"))
    }

    @Test
    fun assetsThatCannotBeCheckedAreNotCandidates() {
        val r = release(
            "v1", 1,
            asset("empty.pbw", size = 0),
            asset("huge.pbw", size = MAX_GIT_PBW_BYTES + 1),
            asset("plain.pbw", url = "http://github.com/someone/face/plain.pbw"),
            asset("elsewhere.pbw", url = "https://evil.example/elsewhere.pbw"),
            asset("face.zip"),
        )
        assertIs<ReleaseSelection.NoAsset>(selectRelease(repo, listOf(r), false, null))
        val ok = release("v1", 1, asset("face.PBW", size = MAX_GIT_PBW_BYTES))
        assertIs<ReleaseSelection.Found>(selectRelease(repo, listOf(ok), false, null))
    }

    @Test
    fun patternTakesTheNameLiterallyExceptDigitRuns() {
        val pattern = Regex(assetPatternFor("my.face(1)-v1.2.0+b7.pbw"))
        assertTrue(pattern.matches("my.face(1)-v1.10.3+b12.pbw"))
        assertFalse(pattern.matches("myXface(1)-v1.10.3+b12.pbw"))
        assertFalse(pattern.matches("my.face(1)-v1.10.3+b12.pbw.zip"))
        assertFalse(pattern.matches("my.face(1)-v.10.3+b12.pbw"))
    }

    @Test
    fun anUpdateIsAnotherTagPublishedLater() {
        val installedAt = Instant.parse("2026-01-03T00:00:00Z")
        assertTrue(isUpdate("v1", installedAt, "v2", release("v2", 4).publishedAt))
        assertFalse(isUpdate("v1", installedAt, "v1", release("v1", 4).publishedAt))
        assertFalse(isUpdate("v1", installedAt, "v0.9", release("v0.9", 2).publishedAt))
        assertFalse(isUpdate("v1", installedAt, "v2", release("v2", 3).publishedAt))
    }
}
