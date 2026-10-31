package com.anopticlabs.gravel.router.common

import android.content.pm.PermissionInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GravelSignersTest {

    private val fdroid = GravelSigners.TRUSTED.first()
    private val self = setOf("aa".repeat(32))
    private val other = setOf("bb".repeat(32))

    @Test
    fun thePermissionCountsOnlyWhenGravelDefinesItAtSignatureLevelUnderAKnownKey() {
        assertTrue(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_SIGNATURE, setOf(fdroid), self))
        assertTrue(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_SIGNATURE, self, self), "a Gravel on this app's own key")
        assertFalse(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_SIGNATURE, other, self), "another key under Gravel's name")
        assertFalse(GravelSigners.accepts("com.example.impostor", PermissionInfo.PROTECTION_SIGNATURE, setOf(fdroid), self), "another package")
        assertFalse(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_NORMAL, setOf(fdroid), self), "a definition any app could hold")
        assertFalse(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_SIGNATURE, setOf(fdroid, "cc".repeat(32)), self), "several signers have no one namespace")
        assertFalse(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_SIGNATURE, null, self), "the definer is not installed")
        assertFalse(GravelSigners.accepts(null, PermissionInfo.PROTECTION_SIGNATURE, setOf(fdroid), self), "no definition at all")
        assertFalse(GravelSigners.accepts(GravelSigners.GRAVEL_PACKAGE, PermissionInfo.PROTECTION_SIGNATURE, other, null), "this app's own signer unknown")
    }

    @Test
    fun theTrustedDigestsAreLowercaseHexSha256() {
        for (digest in GravelSigners.TRUSTED) assertTrue(Regex("[0-9a-f]{64}").matches(digest), digest)
    }
}
