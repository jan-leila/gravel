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
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val builtIn = "c".repeat(64)
    private val store = RouterTrustStore(dir, { now }, defaults = setOf(builtIn))
    private val file = File(dir, RouterTrustStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun certificatesAreAddedRenamedAndRemovedAgain() = runBlocking<Unit> {
        assertNull(store.entries.value)
        assertEquals(TrustEntries.EMPTY, store.load())
        assertEquals(listOf(TrustedCertificate(builtIn, null, builtIn = true, null)), store.certificates())
        assertTrue(store.add(a.uppercase(), "Tasks"))
        now = 2_000L
        assertTrue(store.add(b, null))
        assertEquals(listOf(TrustedSigner(a, "Tasks", 1_000L), TrustedSigner(b, null, 2_000L)), store.read().added)
        assertEquals(store.read(), store.entries.value)
        assertEquals(store.read(), RouterTrustStore(dir).read(), "another instance reads the file")
        assertEquals(setOf(builtIn, a, b), store.trustedDigests())
        now = 3_000L
        assertTrue(store.add(a, "Renamed"), "renames in place")
        assertEquals(listOf(TrustedSigner(b, null, 2_000L), TrustedSigner(a, "Renamed", 3_000L)), store.read().added)
        assertTrue(store.remove(a))
        assertTrue(store.remove(b))
        assertEquals(TrustEntries.EMPTY, store.read())
        assertFalse(file.exists(), "no changes leave no file")
    }

    @Test
    fun aBuiltInCertificateIsRemovedAndRestoredWithoutLeavingTheBuiltInList() = runBlocking<Unit> {
        assertTrue(store.remove(builtIn))
        assertEquals(TrustEntries(removedDefaults = listOf(builtIn)), store.read())
        assertTrue(store.certificates().isEmpty())
        assertTrue(store.trustedDigests().isEmpty())
        assertTrue(store.add(builtIn, "ignored"))
        assertEquals(TrustEntries.EMPTY, store.read())
        assertEquals(listOf(TrustedCertificate(builtIn, null, builtIn = true, null)), store.certificates())
    }

    @Test
    fun anImportReplacesEverythingAndTheListIsCapped() = runBlocking<Unit> {
        assertTrue(store.add(a, "Old"))
        assertTrue(store.replace(TrustEntries(added = listOf(TrustedSigner(b, "New", 9L)), removedDefaults = listOf(builtIn, "junk"))))
        assertEquals(TrustEntries(added = listOf(TrustedSigner(b, "New", 9L)), removedDefaults = listOf(builtIn)), store.read())
        val full = TrustEntries(added = (1..TrustEntries.MAX_ENTRIES).map { TrustedSigner(it.toString(16).padStart(64, '0'), null, it.toLong()) })
        assertTrue(store.replace(full))
        assertFalse(store.add(a, "One more"), "full")
        assertTrue(store.add(full.added.first().sha256, "Renamed"), "renaming does not need room")
    }

    @Test
    fun aMalformedDigestIsRefusedAndAnUnreadableFileChangesNothing() = runBlocking<Unit> {
        assertFalse(store.add("not a digest", null))
        assertFalse(store.remove("not a digest"))
        assertFalse(file.exists())
        file.writeText("{\"added\":")
        assertEquals(TrustEntries.EMPTY, store.read())
        file.writeText("""{"added":[{"sha256":"nope"}],"removedDefaults":["$builtIn"]}""")
        assertEquals(TrustEntries(removedDefaults = listOf(builtIn)), store.read(), "a malformed entry is dropped, the rest kept")
        file.writeText("x".repeat(RouterTrustStore.MAX_CHARS + 1))
        assertEquals(TrustEntries.EMPTY, store.read())
    }
}
