package com.anopticlabs.gravel.notebook.routers

/** The edits the routing screen makes to a [RoutingConfig], each giving a new config to validate and store. */
object RoutingEdits {
    /** The kind's tag with the lowest number no router of Gravel's own has, `switch-2` say; `[prefix]` alone when free. */
    fun newId(config: RoutingConfig, prefix: String): String {
        val taken = config.routers.filter { it.namespace == null }.mapTo(mutableSetOf()) { it.id }
        if (prefix !in taken) return prefix
        return generateSequence(2) { it + 1 }.map { "$prefix-$it" }.first { it !in taken }
    }

    /**
     * [router] in place of the router [replacing] (its own ref unless the edit changed it, as picking
     * another app does), with the entry and every rule that named the old ref now naming the new
     * one; appended when no router has that ref.
     */
    fun withRouter(config: RoutingConfig, router: RouterConfig, replacing: RouterRef = router.ref): RoutingConfig {
        fun RouterRef.moved() = if (this == replacing) router.ref else this
        val routers = config.routers.map { existing ->
            when {
                existing.ref == replacing -> router
                existing is RouterConfig.Switch -> existing.copy(rules = existing.rules.map { it.copy(router = it.router.moved()) }, otherwise = existing.otherwise?.moved())
                else -> existing
            }
        }
        val present = config.routers.any { it.ref == replacing }
        return config.copy(entry = config.entry.moved(), routers = if (present) routers else routers + router)
    }

    /** The switches whose rules or fallback forward to [ref]; a router they name cannot be removed. */
    fun referencedBy(config: RoutingConfig, ref: RouterRef): List<RouterConfig.Switch> =
        config.routers.filterIsInstance<RouterConfig.Switch>().filter { switch -> switch.otherwise == ref || switch.rules.any { it.router == ref } }

    /** Without the router [ref]; null when it is the entry or a switch forwards to it. */
    fun withoutRouter(config: RoutingConfig, ref: RouterRef): RoutingConfig? {
        if (config.entry == ref || referencedBy(config, ref).isNotEmpty()) return null
        return config.copy(routers = config.routers.filterNot { it.ref == ref })
    }

    fun withEntry(config: RoutingConfig, ref: RouterRef): RoutingConfig = config.copy(entry = ref)

    /** With the grant of [ref] for [need] set to [grant]; unchanged when no router has that ref or it has no grants. */
    fun withGrant(config: RoutingConfig, ref: RouterRef, need: Need, grant: Grant): RoutingConfig = config.copy(
        routers = config.routers.map { router ->
            when {
                router.ref != ref -> router
                router is RouterConfig.App -> router.copy(shares = router.shares.with(need, grant))
                router is RouterConfig.Network -> router.copy(shares = router.shares.with(need, grant))
                else -> router
            }
        },
    )

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
        is RouterConfig.App -> "Sends to ${router.packageName}" + sharesSummary(router)
        is RouterConfig.Network -> "Posts to ${hostOf(router.url)}" + sharesSummary(router)
    }

    // Only what is needed is mentioned: a grant for a part the router never asked for changes nothing.
    private fun sharesSummary(router: RouterConfig): String {
        val shares = router.shares ?: return ""
        val parts = router.needed().sortedBy { it.ordinal }.mapNotNull { need ->
            when (shares.of(need)) {
                Grant.ALWAYS -> "shares ${needName(need)}"
                Grant.ASK -> "asks about ${needName(need)}"
                Grant.NEVER -> null
            }
        }
        return parts.joinToString(separator = ", ", prefix = ", ").takeIf { parts.isNotEmpty() }.orEmpty()
    }

    /** The need as a line names it. */
    fun needName(need: Need): String = when (need) {
        Need.LOCATION -> "your location"
        Need.RECORDED_AT -> "the recording time"
    }

    /** The host of an https address, for a line that should not show a token or a path. */
    fun hostOf(url: String): String = url.removePrefix("https://").substringBefore('/').substringBefore('?').ifEmpty { url }
}
