package com.anopticlabs.gravel.notebook.routers

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RouterTrustStoreTest {

    private val dir = Files.createTempDirectory("trust").toFile()
    private var now = 1_000L
    private val store = RouterTrustStore(dir) { now }
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun entriesAreKeptPerPackageAndForgottenAgain() = runBlocking<Unit> {
        assertNull(store.entries.value)
        assertEquals(emptyList(), store.load())
        assertTrue(store.trust("com.example.tasks", a.uppercase()))
        now = 2_000L
        assertTrue(store.trust("com.example.other", b))
        assertEquals(listOf(TrustedRouterApp("com.example.tasks", a, 1_000L), TrustedRouterApp("com.example.other", b, 2_000L)), store.read())
        assertEquals(store.read(), store.entries.value)
        assertEquals(store.read(), RouterTrustStore(dir).read(), "another instance reads the file")
        now = 3_000L
        assertTrue(store.trust("com.example.tasks", b), "replaces the package's entry")
        assertEquals(listOf(TrustedRouterApp("com.example.other", b, 2_000L), TrustedRouterApp("com.example.tasks", b, 3_000L)), store.read())
        assertTrue(store.forget("com.example.tasks"))
        assertEquals(listOf(TrustedRouterApp("com.example.other", b, 2_000L)), store.read())
        assertTrue(store.forget("com.example.other"))
        assertEquals(emptyList(), store.read())
        assertFalse(File(dir, RouterTrustStore.FILE_NAME).exists(), "an empty list leaves no file")
    }

    @Test
    fun anAppWithSeveralSignersIsTrustedByEachOfThem() = runBlocking<Unit> {
        assertTrue(store.trustAll("com.example.tasks", listOf(a, b)))
        assertEquals(listOf(TrustedRouterApp("com.example.tasks", a, 1_000L), TrustedRouterApp("com.example.tasks", b, 1_000L)), store.read())
        assertFalse(store.trustAll("com.example.tasks", listOf(a, "short")))
        assertEquals(2, store.read().size)
    }

    @Test
    fun aMalformedDigestIsRefusedAndAnUnreadableFileTrustsNothing() = runBlocking<Unit> {
        assertFalse(store.trust("com.example.tasks", "not a digest"))
        assertFalse(File(dir, RouterTrustStore.FILE_NAME).exists())
        File(dir, RouterTrustStore.FILE_NAME).writeText("[{\"packageName\":")
        assertEquals(emptyList(), store.read())
        File(dir, RouterTrustStore.FILE_NAME).writeText("""[{"packageName":"com.example.tasks","sha256":"nope","addedAtMillis":1}]""")
        assertEquals(emptyList(), store.read(), "an entry with a malformed digest is dropped")
        File(dir, RouterTrustStore.FILE_NAME).writeText("x".repeat(RouterTrustStore.MAX_CHARS + 1))
        assertEquals(emptyList(), store.read())
    }
}
