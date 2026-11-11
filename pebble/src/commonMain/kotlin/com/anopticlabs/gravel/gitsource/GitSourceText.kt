package com.anopticlabs.gravel.gitsource

const val INVALID_URL_MESSAGE = "Enter an https address of a GitHub, Forgejo or Gitea repository, like https://codeberg.org/owner/repo"

fun GitSourceError.message(): String = when (this) {
    GitSourceError.NETWORK -> "Couldn't reach the repository. Check the connection and try again"
    GitSourceError.RATE_LIMITED -> "GitHub limits how often apps may check without an account. Try again within the hour"
    GitSourceError.NOT_FOUND -> "Repository not found, or it is private"
    GitSourceError.NOT_A_FORGE -> "That site doesn't look like GitHub, Forgejo or Gitea"
    GitSourceError.MALFORMED -> "The repository's answer couldn't be read"
    GitSourceError.NO_RELEASE -> "The repository has no published release"
    GitSourceError.NO_ASSET -> "The newest release has no matching .pbw file"
    GitSourceError.SEVERAL_ASSETS -> "The newest release has several matching .pbw files. Edit the file pattern"
    GitSourceError.CHECK_FAILED -> "The downloaded file isn't the one the release lists, or isn't a watch app"
    GitSourceError.WRONG_APP -> "The release is a different watch app than the one this repository installed, so it was not installed"
    GitSourceError.INSTALL_FAILED -> "Couldn't add the app to the locker"
}

/** A capability as the user reads it; [PKJS_CAPABILITY] is Gravel's own entry for phone-side JavaScript. */
fun capabilityLabel(capability: String): String = when (capability) {
    "location" -> "Location"
    "configurable" -> "Settings page"
    "health" -> "Health data"
    PKJS_CAPABILITY -> "Code that runs on the phone (can use the internet)"
    else -> capability
}

fun GitSourceStatus.label(): String = when (this) {
    GitSourceStatus.UP_TO_DATE -> "Up to date"
    GitSourceStatus.UPDATE_AVAILABLE -> "Update available"
    GitSourceStatus.NEEDS_APPROVAL -> "Update asks for more access"
    GitSourceStatus.REPLACED -> "Replaced outside Git Sources; not updated automatically"
    GitSourceStatus.NOT_INSTALLED -> "Not in the locker"
}
