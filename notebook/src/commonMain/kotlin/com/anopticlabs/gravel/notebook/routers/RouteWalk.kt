package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException

/** Follows the graph from its entry to the router that consumes the text. */
object RouteWalk {
    /** Forwarding routers in a row past which a walk is flagged, whatever the config says. */
    const val MAX_HOPS = 16

    /**
     * The router [text] ends at. A walk that cannot end (no matching rule and no fallback, a
     * missing router, a circle, a rule that is not a regular expression) is deterministic for
     * this text and config, so it fails as a flag with [CaptureFailureReason.NOT_ROUTED];
     * [RoutingConfig.requireWellFormed] keeps most of these out of a stored config, and the walk
     * guards against them anyway.
     */
    fun terminal(config: RoutingConfig, text: RoutedText): RouterConfig {
        var current = config.router(config.entry) ?: notRouted("the entry router does not exist")
        val visited = mutableSetOf<RouterRef>()
        var hops = 0
        while (current.forwardsText) {
            if (!visited.add(current.ref)) notRouted("the routers lead in a circle at ${current.name}")
            if (++hops > MAX_HOPS) notRouted("more than $MAX_HOPS forwarding routers in a row")
            val next = when (current) {
                is RouterConfig.Switch -> pick(current, text)
                is RouterConfig.Time -> pick(current, text)
                is RouterConfig.Place -> pick(current, text)
                else -> error("${current.name} does not forward")
            } ?: notRouted("no rule of ${current.name} matched and it has no fallback")
            current = config.router(next) ?: notRouted("${current.name} forwards to a router that does not exist")
        }
        return current
    }

    // The payload's location is read here inside Gravel; the grants govern only what leaves the phone.
    private fun pick(place: RouterConfig.Place, text: RoutedText): RouterRef? {
        val location = text.location ?: return place.unknown ?: place.otherwise
        return place.rules.firstOrNull { PlaceMatcher.holds(it, location) }?.router ?: place.otherwise
    }

    // The recording's own time, in the zone it was made in; a payload Gravel did not write could carry anything.
    private fun pick(time: RouterConfig.Time, text: RoutedText): RouterRef? {
        val fields = TimeMatcher.fields(text.recordedAt) ?: notRouted("the recording's time could not be read for ${time.name}")
        return time.rules.firstOrNull { TimeMatcher.holds(it, fields) }?.router ?: time.otherwise
    }

    private fun pick(switch: RouterConfig.Switch, text: RoutedText): RouterRef? {
        switch.rules.forEachIndexed { index, rule ->
            val n = index + 1
            val pattern = try {
                SwitchMatcher.compile(rule)
            } catch (e: IllegalArgumentException) {
                notRouted("rule $n of ${switch.name} is not a valid regular expression")
            }
            if (SwitchMatcher.matches(pattern, text.text)) return rule.router
        }
        return switch.otherwise
    }

    private fun notRouted(why: String): Nothing =
        throw CaptureProcessingException(CaptureFailureKind.FLAG, "this recording could not be routed: $why", reason = CaptureFailureReason.NOT_ROUTED)
}
