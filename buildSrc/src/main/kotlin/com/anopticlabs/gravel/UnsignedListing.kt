package com.anopticlabs.gravel

/**
 * Whether this build is the unsigned listing (`-Pgravel.unsigned=true`). androidApp reads it for
 * the applicationId and app name, util for CommonBuildKonfig.UNSIGNED_LISTING; the two must agree,
 * so both read it here.
 */
object UnsignedListing {
    const val PROPERTY = "gravel.unsigned"

    /** [gradleProperty] looks a Gradle property up by name, null when it is unset. */
    fun selected(gradleProperty: (String) -> String?): Boolean = gradleProperty(PROPERTY) == "true"
}
