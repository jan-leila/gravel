package com.anopticlabs.gravel.router.common

/** What one send of a delivery came to. */
sealed interface SendResult {
    data object Sent : SendResult

    /** The delivery can never be sent as it is; the user is told [reason] and it is dropped. */
    data class Refused(val reason: String) : SendResult

    /** Something that may pass: the network, the server; the delivery waits and is tried again. */
    data class Retry(val reason: String) : SendResult
}

/** Sends one delivery to the integration this app is for; blocking, called off the main thread. */
fun interface Sender {
    fun send(delivery: Delivery): SendResult
}

/** The app of a router: what the receiver, the outbox worker, the list provider and the settings screen need from it. */
interface RouterApp {
    val sender: Sender

    /** The routers the app lists beside its declared one; null for an app that lists none. */
    val routerList: RouterList? get() = null

    /** The reason a delivery cannot be taken as the app is set up now, or null; checked before anything is stored. */
    fun refusal(delivery: Delivery): String? = null
}
