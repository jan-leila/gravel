package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTrustTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val old = "c".repeat(64)
    private val never: (String) -> Boolean = { false }
    private val tasks = TrustedRouterApp("com.example.tasks", a, 1L)

    @Test
    fun aDigestIsSixtyFourHexDigitsLowercased() {
        assertEquals(a, RouterTrust.normalizeDigest("  ${"A".repeat(64)} "))
        assertNull(RouterTrust.normalizeDigest("a".repeat(63)))
        assertNull(RouterTrust.normalizeDigest("g".repeat(64)))
        assertNull(RouterTrust.normalizeDigest(""))
    }

    @Test
    fun nothingIsTrustedWithoutAnEntryAndTheDefaultListIsEmptyAsShipped() {
        assertTrue(DEFAULT_TRUSTED_ROUTER_SIGNERS.isEmpty())
        assertFalse(RouterTrust.isTrusted("com.example.tasks", listOf(a), emptyList(), never))
        assertFalse(RouterTrust.isTrusted("com.example.tasks", emptyList(), listOf(tasks), never), "no signer at all")
    }

    @Test
    fun aUserEntryTrustsThatAppAloneAndADefaultSignerTrustsEveryAppItSigned() {
        assertTrue(RouterTrust.isTrusted("com.example.tasks", listOf(a), listOf(tasks), never))
        assertFalse(RouterTrust.isTrusted("com.example.other", listOf(a), listOf(tasks), never), "same signer, another package")
        assertTrue(RouterTrust.isTrusted("com.example.other", listOf(a), emptyList(), never, defaults = setOf(a)))
    }

    @Test
    fun anAppWithSeveralSignersNeedsEveryOneTrusted() {
        assertFalse(RouterTrust.isTrusted("com.example.tasks", listOf(a, b), listOf(tasks), never))
        assertTrue(RouterTrust.isTrusted("com.example.tasks", listOf(a, b), listOf(tasks, TrustedRouterApp("com.example.tasks", b, 2L)), never))
        assertTrue(RouterTrust.isTrusted("com.example.tasks", listOf(a, b), listOf(tasks), never, defaults = setOf(b)))
    }

    @Test
    fun aRotatedSingleSignerStaysTrustedThroughItsLineageButSeveralSignersDoNot() {
        val entry = TrustedRouterApp("com.example.tasks", old, 1L)
        val lineage: (String) -> Boolean = { it == old }
        assertTrue(RouterTrust.isTrusted("com.example.tasks", listOf(a), listOf(entry), lineage))
        assertFalse(RouterTrust.isTrusted("com.example.other", listOf(a), listOf(entry), lineage), "the entry is for another package")
        assertFalse(RouterTrust.isTrusted("com.example.tasks", listOf(a, b), listOf(entry), lineage))
        assertTrue(RouterTrust.isTrusted("com.example.tasks", listOf(a), emptyList(), lineage, defaults = setOf(old)))
    }
}
