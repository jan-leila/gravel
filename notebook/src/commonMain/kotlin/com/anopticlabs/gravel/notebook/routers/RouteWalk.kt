package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import kotlin.time.Duration

/** Follows the graph from its entry to the router that consumes the text. */
object RouteWalk {
    /** Switches in a row past which a walk is flagged, whatever the config says. */
    const val MAX_HOPS = 16

    /**
     * The router [text] ends at. A walk that cannot end (no matching rule and no fallback, a
     * missing router, a circle, a rule that is not a regular expression or that runs past
     * [ruleDeadline]) is deterministic for this text and config, so it fails as a flag with
     * [CaptureFailureReason.NOT_ROUTED]; [RoutingConfig.requireWellFormed] keeps most of these out
     * of a stored config, and the walk guards against them anyway.
     */
    fun terminal(config: RoutingConfig, text: RoutedText, ruleDeadline: Duration = SwitchMatcher.RULE_DEADLINE): RouterConfig {
        var current = config.router(config.entry) ?: notRouted("the entry router does not exist")
        val visited = mutableSetOf<String>()
        var hops = 0
        while (current is RouterConfig.Switch) {
            if (!visited.add(current.id)) notRouted("the routers lead in a circle at ${current.name}")
            if (++hops > MAX_HOPS) notRouted("more than $MAX_HOPS switches in a row")
            val next = pick(current, text, ruleDeadline) ?: notRouted("no rule of ${current.name} matched and it has no fallback")
            current = config.router(next) ?: notRouted("${current.name} forwards to a router that does not exist")
        }
        return current
    }

    private fun pick(switch: RouterConfig.Switch, text: RoutedText, ruleDeadline: Duration): String? {
        switch.rules.forEachIndexed { index, rule ->
            val n = index + 1
            val regex = try {
                SwitchMatcher.compile(rule)
            } catch (e: IllegalArgumentException) {
                notRouted("rule $n of ${switch.name} is not a valid regular expression")
            }
            val hit = try {
                SwitchMatcher.matches(regex, text.text, deadlineAfter(ruleDeadline))
            } catch (e: RuleTimeoutException) {
                notRouted("rule $n of ${switch.name} took too long")
            }
            if (hit) return rule.target
        }
        return switch.otherwise
    }

    private fun notRouted(why: String): Nothing =
        throw CaptureProcessingException(CaptureFailureKind.FLAG, "this recording could not be routed: $why", reason = CaptureFailureReason.NOT_ROUTED)
}
