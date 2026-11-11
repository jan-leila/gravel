package com.anopticlabs.gravel

import com.anopticlabs.gravel.app.SIGNED_APPLICATION_ID
import coredevices.coreapp.BuildConfig
import coredevices.util.CommonBuildKonfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The unsigned listing's warning sends its users to the signed build's F-Droid listing (SIGNED_FDROID_URL),
 * so the ID that link names must be the one the default build installs as, and
 * `-Pgravel.unsigned` must reach both of its readers (androidApp's applicationId, util's
 * CommonBuildKonfig) alike.
 */
class UnsignedListingTest {

    @Test
    fun theBuiltApplicationIdMatchesTheListingTheWarningLinksTo() {
        val expected = if (CommonBuildKonfig.UNSIGNED_LISTING) "com.anopticlabs.gravel" else SIGNED_APPLICATION_ID
        assertEquals(expected, BuildConfig.APPLICATION_ID)
    }
}
