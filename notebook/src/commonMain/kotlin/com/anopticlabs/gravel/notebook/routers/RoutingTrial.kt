package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException

/** One Switch rule that matched in a trial: the switch by name, the words, whether they are cut, and each named group's value. */
data class TrialMatch(val switchName: String, val matched: String, val cut: Boolean, val groups: List<Pair<String, String>>)

/** What the routing does with a sentence the user typed. */
sealed interface Trial {
    /**
     * The walk ended at [router] through [matches]; a router that sends out of the phone would be
     * sent [unmatchedText] in place of the whole text and the request parameters [fields], beside
     * what its grants share.
     */
    data class Ended(val router: RouterConfig, val matches: List<TrialMatch>, val unmatchedText: String, val fields: Map<String, String>) : Trial

    /** The walk could not end; [reason] is the flag a recording would get. */
    data class NotRouted(val reason: String) : Trial
}

/** Runs the routing walk on a sentence without saving, delivering or calling the text model, for the user to try their rules. */
object RoutingTrial {
    /** [recordedAt] and [location] stand in for the recording's, so Time and Place routers decide as they would now and here. */
    fun run(config: RoutingConfig, sentence: String, recordedAt: String, location: RoutedLocation?): Trial {
        val route = try {
            RouteWalk.route(config, RoutedText(TRIAL_ID, recordedAt, sentence, location))
        } catch (e: CaptureProcessingException) {
            return Trial.NotRouted(e.message.orEmpty())
        }
        val text = route.text
        val matches = text.matches.map { match ->
            TrialMatch(
                switchName = config.router(RouterRef.gravel(match.routerId))?.name ?: match.routerId,
                matched = text.slice(match.start, match.end),
                cut = match.cut,
                groups = match.groups.map { it.name to text.slice(it.start, it.end) },
            )
        }
        return Trial.Ended(route.router, matches, text.unmatchedText, route.router.fieldsFor(text))
    }

    private const val TRIAL_ID = "trial"
}

/** The named groups of every Switch rule, the sources a parameter can be mapped to; a rule that does not compile has none. */
fun RoutingConfig.groupNames(): Set<String> = routers.filterIsInstance<RouterConfig.Switch>().flatMap { it.rules }.flatMap { rule ->
    try {
        SwitchMatcher.compile(rule).groupNames
    } catch (e: IllegalArgumentException) {
        emptySet()
    }
}.sorted().toSet()
