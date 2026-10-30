package com.anopticlabs.gravel.notebook.routers

/** The edits the routing screen makes to a [RoutingConfig], each giving a new config to validate and store. */
object RoutingEdits {
    /** The kind's tag with the lowest number no router has, `switch-2` say; `[prefix]` alone when free. */
    fun newId(config: RoutingConfig, prefix: String): String {
        val taken = config.routers.mapTo(mutableSetOf()) { it.id }
        if (prefix !in taken) return prefix
        return generateSequence(2) { it + 1 }.map { "$prefix-$it" }.first { it !in taken }
    }

    /** [router] in place of the router with its id, or appended. */
    fun withRouter(config: RoutingConfig, router: RouterConfig): RoutingConfig {
        val replaced = config.routers.map { if (it.id == router.id) router else it }
        return config.copy(routers = if (replaced.any { it.id == router.id }) replaced else replaced + router)
    }

    /** The switches whose rules or fallback forward to [id]; a router they name cannot be removed. */
    fun referencedBy(config: RoutingConfig, id: String): List<RouterConfig.Switch> =
        config.routers.filterIsInstance<RouterConfig.Switch>().filter { switch -> switch.otherwise == id || switch.rules.any { it.target == id } }

    /** Without the router [id]; null when it is the entry or a switch forwards to it. */
    fun withoutRouter(config: RoutingConfig, id: String): RoutingConfig? {
        if (config.entry == id || referencedBy(config, id).isNotEmpty()) return null
        return config.copy(routers = config.routers.filterNot { it.id == id })
    }

    fun withEntry(config: RoutingConfig, id: String): RoutingConfig = config.copy(entry = id)

    /** The kind as the screen names it. */
    fun kindName(router: RouterConfig): String = when (router) {
        is RouterConfig.Note -> "Note"
        is RouterConfig.Reminder -> "Reminder"
        is RouterConfig.Switch -> "Switch"
        is RouterConfig.App -> "App"
        is RouterConfig.Network -> "Network"
    }

    /** The id prefix a new router of the same kind as [router] gets. */
    fun idPrefix(router: RouterConfig): String = kindName(router).lowercase()

    /** One line under a router's name: what it does with the text. */
    fun summary(router: RouterConfig, config: RoutingConfig): String = when (router) {
        is RouterConfig.Note -> "Writes the text as a note"
        is RouterConfig.Reminder -> "Sets a reminder from the text"
        is RouterConfig.Switch -> {
            val rules = when (router.rules.size) {
                0 -> "No rules"
                1 -> "1 rule"
                else -> "${router.rules.size} rules"
            }
            val otherwise = router.otherwise?.let { config.router(it)?.name }
            if (otherwise == null) rules else "$rules, else to $otherwise"
        }
        is RouterConfig.App -> "Sends to ${router.packageName}"
        is RouterConfig.Network -> "Posts to ${hostOf(router.url)}"
    }

    /** The host of an https address, for a line that should not show a token or a path. */
    fun hostOf(url: String): String = url.removePrefix("https://").substringBefore('/').substringBefore('?').ifEmpty { url }
}
