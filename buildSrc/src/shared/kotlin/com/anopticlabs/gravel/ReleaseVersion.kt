package com.anopticlabs.gravel

/**
 * The release numbers `androidApp/version.properties` declares. androidApp's build stamps them and
 * runs VerifyReleaseVersionFile with them; FdroidGuardrailsTest compiles this same file into its
 * test source set, so it must not use the Gradle API.
 */
data class ReleaseVersion(val code: Int, val name: String) {

    /**
     * True when [tagsAtHead] holds no release-shaped tag or holds [name]: F-Droid takes the tag
     * name of the checkout as the versionName, so a release tag must name this version.
     */
    fun matchesTagsAtHead(tagsAtHead: Collection<String>): Boolean {
        val releaseTags = tagsAtHead.filter { RELEASE_TAG.matches(it) }
        return releaseTags.isEmpty() || name in releaseTags
    }

    companion object {
        const val PATH = "androidApp/version.properties"

        private val CODE = Regex("""versionCode=(\d+)""")
        private val NAME = Regex("""versionName=((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))""")
        private val RELEASE_TAG = Regex("""\d+\.\d+\.\d+""")

        /** Throws IllegalArgumentException naming what is wrong with [text]. */
        fun parse(text: String): ReleaseVersion {
            val code = line(text, "versionCode", CODE).toIntOrNull()
            requireNotNull(code) { "$PATH declares a versionCode too large for an Int" }
            return ReleaseVersion(code, line(text, "versionName", NAME))
        }

        // Other lines and any order are allowed, but each key must appear on exactly one line and
        // that line must be the whole key=value: the recipe's UpdateCheckData regex finds
        // versionCode=(\d+) anywhere in the file, so a second mention, even in a comment, could
        // be the one it reads. Lines split on LF only, so a CRLF file fails, as it does the
        // workflows' line-anchored sed.
        private fun line(text: String, key: String, shape: Regex): String {
            val mentions = text.split('\n').filter { "$key=" in it }
            require(mentions.size == 1) {
                "$PATH must mention $key= on exactly one line, but has ${mentions.size}:\n$text"
            }
            val match = shape.matchEntire(mentions.single())
            requireNotNull(match) { "$PATH line ${mentions.single()} does not have the shape ${shape.pattern}" }
            return match.groupValues[1]
        }
    }
}
