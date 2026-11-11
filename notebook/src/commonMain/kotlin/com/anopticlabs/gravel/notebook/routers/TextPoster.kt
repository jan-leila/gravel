package com.anopticlabs.gravel.notebook.routers

/** What one POST came to: the status the server answered with, or how the transport failed. */
sealed interface PostResult {
    data class Answered(val status: Int) : PostResult

    /** Nothing reached the server: the name did not resolve, the connection was refused or the connect timed out. */
    data object NotConnected : PostResult

    /** A failure after connecting: a read timeout, a reset, a refused certificate. [reason] names the exception. */
    data class Failed(val reason: String) : PostResult
}

/** Posts a JSON body; the app binds it to its HTTP client. Never throws for a transport failure. */
interface TextPoster {
    suspend fun post(url: String, bearerToken: String?, bodyJson: String): PostResult
}
