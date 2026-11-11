package com.anopticlabs.gravel.gitsource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitRepoTest {
    @Test
    fun githubAddressesInTheirUsualForms() {
        val expected = GitRepo(GitHost.GITHUB, "https://github.com", "someone", "my-face")
        assertEquals(expected, parseGitRepoUrl("https://github.com/someone/my-face"))
        assertEquals(expected, parseGitRepoUrl("  https://github.com/someone/my-face.git  "))
        assertEquals(expected, parseGitRepoUrl("https://www.github.com/someone/my-face/releases/latest"))
        assertEquals(expected, parseGitRepoUrl("HTTPS://GitHub.com/someone/my-face/"))
        assertEquals("https://api.github.com/repos/someone/my-face/releases", expected.releasesApiUrl)
    }

    @Test
    fun anyOtherHostIsTakenAsAForge() {
        val codeberg = parseGitRepoUrl("https://codeberg.org/someone/watchapp")
        assertEquals(GitRepo(GitHost.FORGEJO, "https://codeberg.org", "someone", "watchapp"), codeberg)
        assertEquals("https://codeberg.org/api/v1/repos/someone/watchapp/releases", codeberg?.releasesApiUrl)
        assertEquals("https://git.example.net:3000", parseGitRepoUrl("https://git.example.net:3000/a/b")?.baseUrl)
        assertEquals("https://git.example.net", parseGitRepoUrl("https://git.example.net:443/a/b")?.baseUrl)
        assertEquals("codeberg.org/someone/watchapp", codeberg?.displayName)
    }

    @Test
    fun refusesWhatIsNotAPlainHttpsRepoAddress() {
        listOf(
            "http://github.com/someone/face",
            "github.com/someone/face",
            "https://github.com/someone",
            "https://github.com/",
            "https://user:pass@codeberg.org/a/b",
            "https://codeberg.org/../b",
            "https://codeberg.org/a/..",
            "https://codeberg.org/a%2Fb/c",
            "https://codeberg.org/a b/c",
            "ftp://codeberg.org/a/b",
            "",
        ).forEach { assertNull(parseGitRepoUrl(it), it) }
    }

    @Test
    fun originIsHttpsOnly() {
        assertEquals("https://objects.example.com", httpsOrigin("https://Objects.Example.com/x?y=1"))
        assertNull(httpsOrigin("http://example.com/x"))
        assertNull(httpsOrigin("example.com/x"))
    }
}
