package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTrustTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val old = "c".repeat(64)
    private val never: (String) -> Boolean = { false }

    @Test
    fun aDigestIsSixtyFourHexDigitsLowercased() {
        assertEquals(a, RouterTrust.normalizeDigest("  ${"A".repeat(64)} "))
        assertNull(RouterTrust.normalizeDigest("a".repeat(63)))
        assertNull(RouterTrust.normalizeDigest("g".repeat(64)))
        assertNull(RouterTrust.normalizeDigest(""))
        assertEquals("Tasks", RouterTrust.normalizeLabel("  Tasks\u0000 "))
        assertNull(RouterTrust.normalizeLabel(" "))
        assertEquals(TrustEntries.MAX_LABEL_CHARS, RouterTrust.normalizeLabel("x".repeat(100))!!.length)
    }

    @Test
    fun nothingIsTrustedWithoutAnEntryBeyondTheBuiltInSigners() {
        for (digest in DEFAULT_TRUSTED_ROUTER_SIGNERS) assertNotNull(RouterTrust.normalizeDigest(digest), digest)
        assertTrue(RouterTrust.certificates(TrustEntries.EMPTY, emptySet()).isEmpty())
        assertFalse(RouterTrust.isTrusted(a, emptySet(), never))
    }

    @Test
    fun theListInForceIsTheBuiltInOnesLessTheRemovedPlusTheAdded() {
        val entries = TrustEntries(added = listOf(TrustedSigner(b, "Tasks", 5L), TrustedSigner(a, "Mine", 6L)), removedDefaults = listOf(old))
        val listed = RouterTrust.certificates(entries, defaults = setOf(a, old))
        assertEquals(
            listOf(TrustedCertificate(a, "Mine", builtIn = true, 6L), TrustedCertificate(b, "Tasks", builtIn = false, 5L)),
            listed,
            "a removed built-in is gone, an added entry for a built-in only names it",
        )
        assertEquals(setOf(a, b), RouterTrust.trustedDigests(entries, defaults = setOf(a, old)))
        assertEquals(listOf(TrustedCertificate(a, null, builtIn = true, null)), RouterTrust.certificates(TrustEntries.EMPTY, defaults = setOf(a)))
    }

    @Test
    fun aSignerIsTrustedByItsCertificateOrByOneEarlierInItsLineage() {
        assertTrue(RouterTrust.isTrusted(a, setOf(a, b), never))
        assertFalse(RouterTrust.isTrusted(old, setOf(a, b), never))
        val lineage: (String) -> Boolean = { it == old }
        assertTrue(RouterTrust.isTrusted(a, setOf(old), lineage))
        assertFalse(RouterTrust.isTrusted(a, setOf(b), lineage))
    }

    @Test
    fun cleaningDropsMalformedDigestsKeepsADigestOnceAndCaps() {
        val entries = TrustEntries(
            added = listOf(TrustedSigner("nope", "x"), TrustedSigner(a.uppercase(), "First", 1L), TrustedSigner(a, "Second", 2L), TrustedSigner(b, " ", 3L)),
            removedDefaults = listOf("short", old, old),
        )
        assertEquals(TrustEntries(added = listOf(TrustedSigner(a, "Second", 2L), TrustedSigner(b, null, 3L)), removedDefaults = listOf(old)), entries.cleaned())
        val many = TrustEntries(added = (0..TrustEntries.MAX_ENTRIES).map { TrustedSigner(it.toString(16).padStart(64, '0'), null, it.toLong()) })
        val capped = many.cleaned()
        assertEquals(TrustEntries.MAX_ENTRIES, capped.added.size)
        assertEquals(TrustEntries.MAX_ENTRIES.toLong(), capped.added.last().addedAtMillis, "the newest are kept")
    }
}
