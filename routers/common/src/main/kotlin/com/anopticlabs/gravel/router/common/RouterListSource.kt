package com.anopticlabs.gravel.router.common

/**
 * Where an app's listed routers come from, for its settings screen: [Fetched] from the service
 * (projects, channels, accounts, contacts), or [Declared] by the user in the app.
 */
sealed interface RouterListSource {
    val title: String
    val intro: String

    /**
     * [fetch] reads the service's list, blocking, and throws an exception whose message the
     * screen shows when it cannot; a router new to the list is shared with Gravel when
     * [sharedByDefault], else only once the user ticks it.
     */
    class Fetched(override val title: String, override val intro: String, val sharedByDefault: Boolean, val fetch: () -> List<ListedRouter>) : RouterListSource

    /** [make] builds a router from the values of [fields] the user typed, or throws an IllegalArgumentException saying what is wrong. */
    class Declared(override val title: String, override val intro: String, val fields: List<Field>, val make: (Map<String, String>) -> ListedRouter) : RouterListSource
}
