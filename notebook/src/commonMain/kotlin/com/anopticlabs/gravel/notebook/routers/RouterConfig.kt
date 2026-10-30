package com.anopticlabs.gravel.notebook.routers

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Names one router: its [id] within a [namespace]. Routers the user makes in Gravel's settings have
 * no namespace (the key is absent in the stored JSON); a router another app declares is in the
 * namespace of the certificate that signed that app (its lowercase hex SHA-256), so two apps can
 * only share a namespace by sharing a signing key, and nothing an app declares can name one of
 * Gravel's own routers.
 */
@Serializable
data class RouterRef(val namespace: String?, val id: String) {
    companion object {
        /** One of Gravel's own routers. */
        fun gravel(id: String) = RouterRef(null, id)
    }
}

/**
 * The parts of a delivery beyond the text: where the recording was made and when. A router that
 * sends out of the phone receives one only when it needs it and the user granted it (see [Shares]).
 * The serial names are the words of the router API's `needs` meta-data (`ROUTER_API.md`).
 */
@Serializable
enum class Need {
    @SerialName("location") LOCATION,
    @SerialName("recorded_at") RECORDED_AT,
}

/** What the user decided about one [Need] for one router: ask at each recording, always send it, never send it. */
@Serializable
enum class Grant {
    @SerialName("ask") ASK,
    @SerialName("always") ALWAYS,
    @SerialName("never") NEVER,
}

/** The user's grant for each [Need]; everything is asked about until the user decides. */
@Serializable
data class Shares(val location: Grant = Grant.ASK, val recordedAt: Grant = Grant.ASK) {
    fun of(need: Need): Grant = when (need) {
        Need.LOCATION -> location
        Need.RECORDED_AT -> recordedAt
    }

    fun with(need: Need, grant: Grant): Shares = when (need) {
        Need.LOCATION -> copy(location = grant)
        Need.RECORDED_AT -> copy(recordedAt = grant)
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
    val namespace: String?
    val id: String
    val name: String

    val ref: RouterRef get() = RouterRef(namespace, id)

    /** Whether a recording routed here waits for the user to confirm it first; only a router that consumes the text can ask. */
    val confirm: Boolean get() = false

    /** Writes the text as a note, titled and tagged by the text model. */
    @Serializable
    @SerialName("note")
    data class Note(
        override val id: String,
        override val name: String,
        override val namespace: String? = null,
        override val confirm: Boolean = false,
    ) : RouterConfig

    /** Sets a reminder: the message from the text model, the time read from the words. */
    @Serializable
    @SerialName("reminder")
    data class Reminder(
        override val id: String,
        override val name: String,
        override val namespace: String? = null,
        override val confirm: Boolean = false,
    ) : RouterConfig

    /** Forwards to the router of the first rule whose pattern matches, else to [otherwise]. */
    @Serializable
    @SerialName("switch")
    data class Switch(
        override val id: String,
        override val name: String,
        val rules: List<SwitchRule> = emptyList(),
        val otherwise: RouterRef? = null,
        override val namespace: String? = null,
    ) : RouterConfig

    /**
     * Forwards to the router of the first rule whose conditions all hold for the time the recording
     * was made, in the zone it was made in, else to [otherwise]. The fields and their units are in
     * [TimeField].
     */
    @Serializable
    @SerialName("time")
    data class Time(
        override val id: String,
        override val name: String,
        val rules: List<TimeRule> = emptyList(),
        val otherwise: RouterRef? = null,
        override val namespace: String? = null,
    ) : RouterConfig

    /**
     * Forwards to the router of the first rule whose spot the recording was made within (or outside
     * of, for a rule with [PlaceRule.within] off), else to [otherwise]; a recording with no location
     * goes to [unknown], or to [otherwise] when that is not set.
     */
    @Serializable
    @SerialName("place")
    data class Place(
        override val id: String,
        override val name: String,
        val rules: List<PlaceRule> = emptyList(),
        val otherwise: RouterRef? = null,
        val unknown: RouterRef? = null,
        override val namespace: String? = null,
    ) : RouterConfig

    /**
     * Forwards to a router another app declares (`ROUTER_API.md`): the receiver, named by package
     * and class, that declared [id] when the user picked it, in an app signed by [namespace].
     * [needs] is what the receiver declared it wants beyond the text, as read when it was picked;
     * [shares] what the user grants of that.
     */
    @Serializable
    @SerialName("app")
    data class App(
        override val id: String,
        override val name: String,
        val packageName: String,
        val receiverClass: String,
        override val namespace: String,
        val needs: Set<Need> = emptySet(),
        val shares: Shares = Shares(),
        override val confirm: Boolean = false,
    ) : RouterConfig

    /**
     * Posts the text as JSON to [url]; [bearerToken], when set, goes in the Authorization header.
     * [shares] is what goes with the text: the user is also the one who set the server up, so a
     * grant is the only declaration of need there is.
     */
    @Serializable
    @SerialName("network")
    data class Network(
        override val id: String,
        override val name: String,
        val url: String,
        val bearerToken: String? = null,
        override val namespace: String? = null,
        val shares: Shares = Shares(),
        override val confirm: Boolean = false,
    ) : RouterConfig
}

/** The grants of a router that sends out of the phone; null for one the Notebook consumes. */
val RouterConfig.shares: Shares?
    get() = when (this) {
        is RouterConfig.App -> shares
        is RouterConfig.Network -> shares
        is RouterConfig.Note, is RouterConfig.Reminder, is RouterConfig.Switch, is RouterConfig.Time, is RouterConfig.Place -> null
    }

/** What a delivery to this router may carry beyond the text: an app's declared needs, a server's every need not refused. */
fun RouterConfig.needed(): Set<Need> = when (this) {
    is RouterConfig.App -> needs
    is RouterConfig.Network -> Need.entries.filterTo(mutableSetOf()) { shares.of(it) != Grant.NEVER }
    is RouterConfig.Note, is RouterConfig.Reminder, is RouterConfig.Switch, is RouterConfig.Time, is RouterConfig.Place -> emptySet()
}

/** The needs the user has to be asked about before a delivery to this router. */
fun RouterConfig.asked(): Set<Need> = needed().filterTo(mutableSetOf()) { shares?.of(it) == Grant.ASK }

/** What a delivery to this router carries, given the needs the user [granted] for this one recording; a one-time grant answers an Ask, never a Never. */
fun RouterConfig.shared(granted: Set<Need>): Set<Need> = needed().filterTo(mutableSetOf()) {
    when (shares?.of(it)) {
        Grant.ALWAYS -> true
        Grant.ASK -> it in granted
        Grant.NEVER, null -> false
    }
}

/** [pattern] is a regular expression looked for anywhere in the text; [router] is where a match goes. */
@Serializable
data class SwitchRule(val pattern: String, val router: RouterRef, val ignoreCase: Boolean = true)

/** A circle on the map: [radiusMeters] round a point, called [label] in the settings. */
@Serializable
data class Spot(val label: String, val latitude: Double, val longitude: Double, val radiusMeters: Int)

/** Where a recording made [within] [spot] goes, or one made outside it when [within] is off. */
@Serializable
data class PlaceRule(val spot: Spot, val within: Boolean, val router: RouterRef)

/** Where a recording goes when every one of [conditions] holds for its time. */
@Serializable
data class TimeRule(val conditions: List<TimeCondition>, val router: RouterRef)

/**
 * [field] is between [from] and [to], both included; with [from] above [to] the range wraps round
 * the field's cycle (22:00 to 06:00, Friday to Monday), except for the year, which has none.
 */
@Serializable
data class TimeCondition(val field: TimeField, val from: Int, val to: Int)

/** What a [TimeCondition] reads off the recording's local time, in the units of [TimeMatcher]. */
@Serializable
enum class TimeField {
    /** Minutes since midnight, 0 to 1439. */
    @SerialName("time_of_day") TIME_OF_DAY,

    /** 1 (Monday) to 7 (Sunday). */
    @SerialName("day_of_week") DAY_OF_WEEK,
    @SerialName("day_of_month") DAY_OF_MONTH,
    @SerialName("day_of_year") DAY_OF_YEAR,

    /** 1 to 5: the first seven days of the month are week 1. */
    @SerialName("week_of_month") WEEK_OF_MONTH,

    /** The ISO-8601 week, 1 to 53. */
    @SerialName("week_of_year") WEEK_OF_YEAR,
    @SerialName("month") MONTH,
    @SerialName("year") YEAR,
}

/** The routers this one forwards to; empty for one that consumes the text. */
val RouterConfig.forwards: List<RouterRef>
    get() = when (this) {
        is RouterConfig.Switch -> rules.map { it.router } + listOfNotNull(otherwise)
        is RouterConfig.Time -> rules.map { it.router } + listOfNotNull(otherwise)
        is RouterConfig.Place -> rules.map { it.router } + listOfNotNull(otherwise, unknown)
        is RouterConfig.Note, is RouterConfig.Reminder, is RouterConfig.App, is RouterConfig.Network -> emptyList()
    }

/** Whether this router hands the text on rather than consuming it. */
val RouterConfig.forwardsText: Boolean get() = this is RouterConfig.Switch || this is RouterConfig.Time || this is RouterConfig.Place

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
     * value past its cap) or one that breaks the namespace rule: Gravel's own kinds have no
     * namespace and an app router is in a signer's, never the other way round.
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
                is RouterConfig.Note, is RouterConfig.Reminder, is RouterConfig.Switch, is RouterConfig.Time, is RouterConfig.Place, is RouterConfig.Network ->
                    require(router.namespace == null) { "${router.name} is one of Gravel's own routers and cannot be in a namespace." }
            }
        }
        routers.groupBy { it.ref }.values.firstOrNull { it.size > 1 }?.let { shared ->
            throw IllegalArgumentException("Two routers share the id ${shared.first().id}${shared.first().namespace?.let { " in the namespace $it" }.orEmpty()}.")
        }
        require(router(entry) != null) { "The entry router does not exist." }
        routers.forEach { router ->
            when (router) {
                is RouterConfig.Switch -> requireWellFormed(router)
                is RouterConfig.Time -> requireWellFormed(router)
                is RouterConfig.Place -> requireWellFormed(router)
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

    private fun requireWellFormed(time: RouterConfig.Time) {
        require(time.rules.size <= MAX_RULES) { "${time.name} can have at most $MAX_RULES rules." }
        time.rules.forEachIndexed { index, rule ->
            val n = index + 1
            require(rule.conditions.size in 1..MAX_CONDITIONS) { "Rule $n of ${time.name} needs 1 to $MAX_CONDITIONS conditions." }
            rule.conditions.forEach { condition ->
                require(TimeMatcher.isWellFormed(condition)) { "Rule $n of ${time.name} has a ${TimeMatcher.fieldName(condition.field)} outside its range." }
            }
            require(router(rule.router) != null) { "Rule $n of ${time.name} forwards to a router that does not exist." }
        }
        time.otherwise?.let { require(router(it) != null) { "${time.name} falls back to a router that does not exist." } }
    }

    private fun requireWellFormed(place: RouterConfig.Place) {
        require(place.rules.size <= MAX_RULES) { "${place.name} can have at most $MAX_RULES rules." }
        place.rules.forEachIndexed { index, rule ->
            val n = index + 1
            val spot = rule.spot
            require(spot.label.isNotBlank() && spot.label.length <= MAX_NAME_CHARS && spot.label.trim() == spot.label) {
                "Rule $n of ${place.name} needs a place name of up to $MAX_NAME_CHARS characters."
            }
            require(PlaceMatcher.isWellFormed(spot)) {
                "Rule $n of ${place.name} needs a latitude, a longitude and a radius of ${PlaceMatcher.MIN_RADIUS_METERS} to ${PlaceMatcher.MAX_RADIUS_METERS} metres."
            }
            require(router(rule.router) != null) { "Rule $n of ${place.name} forwards to a router that does not exist." }
        }
        place.otherwise?.let { require(router(it) != null) { "${place.name} falls back to a router that does not exist." } }
        place.unknown?.let { require(router(it) != null) { "${place.name} sends recordings without a location to a router that does not exist." } }
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

    // A circle can only run through the routers that forward.
    private fun requireNoCircle() {
        val forwarding = routers.filter { it.forwardsText }.associateBy { it.ref }
        val done = mutableSetOf<RouterRef>()
        fun visit(ref: RouterRef, path: List<RouterRef>) {
            val router = forwarding[ref] ?: return
            if (ref in path) throw IllegalArgumentException("The rules of ${router.name} lead back to ${router.name}.")
            if (!done.add(ref)) return
            router.forwards.forEach { visit(it, path + ref) }
        }
        forwarding.keys.forEach { visit(it, emptyList()) }
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
        const val MAX_CONDITIONS = 4
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
