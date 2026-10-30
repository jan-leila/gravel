package com.anopticlabs.gravel.notebook.routers

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Names one router: its [id] within a [namespace]. Routers the user makes in Gravel's settings are
 * in [GRAVEL]; a router another app declares is in the namespace of the certificate that signed
 * that app (its lowercase hex SHA-256), so two apps can only share a namespace by sharing a
 * signing key, and nothing an app declares can name a router in Gravel's own namespace.
 */
@Serializable
data class RouterRef(val namespace: String, val id: String) {
    companion object {
        const val GRAVEL = "gravel"

        fun gravel(id: String) = RouterRef(GRAVEL, id)
    }
}

/**
 * One router in the graph a voice recording's transcript goes through. A router either forwards
 * the whole [RoutedText] to another router, named by its [ref], or consumes it; [name] is what the
 * user sees. The type tags and field names are a stored format (the `routing` store file): a new
 * kind gets a new tag, and a tag is never reused.
 */
@Serializable
sealed interface RouterConfig {
    val namespace: String
    val id: String
    val name: String

    val ref: RouterRef get() = RouterRef(namespace, id)

    /** Writes the text as a note, titled and tagged by the text model. */
    @Serializable
    @SerialName("note")
    @OptIn(ExperimentalSerializationApi::class)
    data class Note(
        override val id: String,
        override val name: String,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val namespace: String = RouterRef.GRAVEL,
    ) : RouterConfig

    /** Sets a reminder: the message from the text model, the time read from the words. */
    @Serializable
    @SerialName("reminder")
    @OptIn(ExperimentalSerializationApi::class)
    data class Reminder(
        override val id: String,
        override val name: String,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val namespace: String = RouterRef.GRAVEL,
    ) : RouterConfig

    /** Forwards to the router of the first rule whose pattern matches, else to [otherwise]. */
    @Serializable
    @SerialName("switch")
    @OptIn(ExperimentalSerializationApi::class)
    data class Switch(
        override val id: String,
        override val name: String,
        val rules: List<SwitchRule> = emptyList(),
        val otherwise: RouterRef? = null,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val namespace: String = RouterRef.GRAVEL,
    ) : RouterConfig

    /**
     * Forwards to a router another app declares (`ROUTER_API.md`): the receiver, named by package
     * and class, that declared [id] when the user picked it, in an app signed by [namespace].
     */
    @Serializable
    @SerialName("app")
    data class App(
        override val id: String,
        override val name: String,
        val packageName: String,
        val receiverClass: String,
        override val namespace: String,
    ) : RouterConfig

    /** Posts the text as JSON to [url]; [bearerToken], when set, goes in the Authorization header. */
    @Serializable
    @SerialName("network")
    @OptIn(ExperimentalSerializationApi::class)
    data class Network(
        override val id: String,
        override val name: String,
        val url: String,
        val bearerToken: String? = null,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val namespace: String = RouterRef.GRAVEL,
    ) : RouterConfig
}

/** [pattern] is a regular expression looked for anywhere in the text; [router] is where a match goes. */
@Serializable
data class SwitchRule(val pattern: String, val router: RouterRef, val ignoreCase: Boolean = true)

/**
 * The whole graph: the [entry] router the transcript goes to first, and every router by ref. The
 * version is always written, so a file names the format it was written in.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RoutingConfig(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("v") val version: Int = VERSION,
    val entry: RouterRef,
    val routers: List<RouterConfig>,
) {
    fun router(ref: RouterRef): RouterConfig? = routers.firstOrNull { it.ref == ref }

    /**
     * Refuses, with a sentence the settings screen can show, a graph the walk could not follow (a
     * missing entry or target, a rule that is not a regular expression, a circle of switches, a
     * value past its cap) or one that breaks the namespace rule: Gravel's own kinds are in
     * [RouterRef.GRAVEL] and an app router is in a signer's namespace, never the other way round.
     * Nothing here reaches outside the config.
     */
    fun requireWellFormed() {
        require(version == VERSION) { "This routing was saved by a newer version of Gravel." }
        require(routers.isNotEmpty()) { "There has to be at least one router." }
        require(routers.size <= MAX_ROUTERS) { "There can be at most $MAX_ROUTERS routers." }
        routers.forEach { router ->
            require(isRouterId(router.id)) { "A router id has to be 1 to $MAX_ID_CHARS lowercase letters, digits or dashes." }
            require(router.name.isNotBlank() && router.name.length <= MAX_NAME_CHARS && router.name.trim() == router.name) {
                "A router needs a name of up to $MAX_NAME_CHARS characters."
            }
            when (router) {
                is RouterConfig.App -> require(RouterTrust.normalizeDigest(router.namespace) == router.namespace) {
                    "${router.name} needs the SHA-256 of its app's signing certificate as its namespace."
                }
                is RouterConfig.Note, is RouterConfig.Reminder, is RouterConfig.Switch, is RouterConfig.Network ->
                    require(router.namespace == RouterRef.GRAVEL) { "${router.name} has to be in Gravel's own namespace." }
            }
        }
        routers.groupBy { it.ref }.values.firstOrNull { it.size > 1 }?.let { shared ->
            throw IllegalArgumentException("Two routers share the id ${shared.first().id} in the namespace ${shared.first().namespace}.")
        }
        require(router(entry) != null) { "The entry router does not exist." }
        routers.forEach { router ->
            when (router) {
                is RouterConfig.Switch -> requireWellFormed(router)
                is RouterConfig.Network -> requireWellFormed(router)
                is RouterConfig.App -> requireWellFormed(router)
                is RouterConfig.Note, is RouterConfig.Reminder -> Unit
            }
        }
        requireNoCircle()
    }

    private fun requireWellFormed(switch: RouterConfig.Switch) {
        require(switch.rules.size <= MAX_RULES) { "${switch.name} can have at most $MAX_RULES rules." }
        switch.rules.forEachIndexed { index, rule ->
            val n = index + 1
            require(rule.pattern.isNotEmpty() && rule.pattern.length <= MAX_PATTERN_CHARS) {
                "Rule $n of ${switch.name} needs a pattern of up to $MAX_PATTERN_CHARS characters."
            }
            try {
                SwitchMatcher.compile(rule)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Rule $n of ${switch.name} is not a valid regular expression.", e)
            }
            require(router(rule.router) != null) { "Rule $n of ${switch.name} forwards to a router that does not exist." }
        }
        switch.otherwise?.let { require(router(it) != null) { "${switch.name} falls back to a router that does not exist." } }
    }

    private fun requireWellFormed(network: RouterConfig.Network) {
        require(isHttpsUrl(network.url)) { "${network.name} needs an https:// address of up to $MAX_URL_CHARS characters." }
        network.bearerToken?.let {
            require(it.isNotEmpty() && it.length <= MAX_TOKEN_CHARS && it.none { c -> c.isWhitespace() || c.isISOControl() }) {
                "The token of ${network.name} has to be up to $MAX_TOKEN_CHARS characters with no spaces."
            }
        }
    }

    private fun requireWellFormed(app: RouterConfig.App) {
        require(PACKAGE_NAME.matches(app.packageName)) { "${app.name} names an app with an invalid package name." }
        require(app.receiverClass.isNotEmpty() && app.receiverClass.length <= MAX_CLASS_CHARS && CLASS_NAME.matches(app.receiverClass)) {
            "${app.name} names an app router with an invalid class name."
        }
    }

    // Switches are the only routers that forward, so a circle can only run through them.
    private fun requireNoCircle() {
        val switches = routers.filterIsInstance<RouterConfig.Switch>().associateBy { it.ref }
        val done = mutableSetOf<RouterRef>()
        fun visit(ref: RouterRef, path: List<RouterRef>) {
            val switch = switches[ref] ?: return
            if (ref in path) throw IllegalArgumentException("The rules of ${switch.name} lead back to ${switch.name}.")
            if (!done.add(ref)) return
            (switch.rules.map { it.router } + listOfNotNull(switch.otherwise)).forEach { visit(it, path + ref) }
        }
        switches.keys.forEach { visit(it, emptyList()) }
    }

    companion object {
        const val VERSION = 1

        /** The id of the router the default graph consists of. */
        const val NOTEBOOK_ROUTER_ID = "notebook"

        /** Every transcript becomes a note. */
        val DEFAULT = RoutingConfig(entry = RouterRef.gravel(NOTEBOOK_ROUTER_ID), routers = listOf(RouterConfig.Note(NOTEBOOK_ROUTER_ID, "Notebook")))

        const val MAX_ROUTERS = 64
        const val MAX_ID_CHARS = 32
        const val MAX_NAME_CHARS = 64
        const val MAX_RULES = 32
        const val MAX_PATTERN_CHARS = 256
        const val MAX_URL_CHARS = 2048
        const val MAX_TOKEN_CHARS = 512
        const val MAX_CLASS_CHARS = 256

        private val ROUTER_ID = Regex("[a-z0-9-]{1,$MAX_ID_CHARS}")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

        fun isRouterId(id: String): Boolean = ROUTER_ID.matches(id)

        /** `https://` with a host, no whitespace or control characters, within the cap. */
        fun isHttpsUrl(url: String): Boolean =
            url.length in (HTTPS.length + 1)..MAX_URL_CHARS &&
                url.startsWith(HTTPS) &&
                url[HTTPS.length] != '/' &&
                url.none { it.isWhitespace() || it.isISOControl() }

        private const val HTTPS = "https://"
    }
}
