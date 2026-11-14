package com.anopticlabs.gravel.notebook.routers

/** What changed between two routings, as lines for the user to read before a change is kept. */
object RoutingDiff {
    fun describe(before: RoutingConfig, after: RoutingConfig): List<String> {
        val lines = mutableListOf<String>()
        val old = before.routers.associateBy { it.ref }
        val new = after.routers.associateBy { it.ref }
        if (before.entry != after.entry) lines += "The entry router is now ${nameOf(after, after.entry)}."
        after.routers.filter { it.ref !in old }.forEach { lines += "Added ${RoutingEdits.kindName(it)} '${it.name}'." }
        before.routers.filter { it.ref !in new }.forEach { lines += "Removed ${RoutingEdits.kindName(it)} '${it.name}'." }
        for ((ref, now) in new) {
            val was = old[ref] ?: continue
            if (was == now) continue
            if (was::class != now::class) {
                lines += "'${now.name}' is now a ${RoutingEdits.kindName(now)}."
                continue
            }
            if (was.name != now.name) lines += "Renamed '${was.name}' to '${now.name}'."
            lines += changes(was, now, after)
        }
        return lines.ifEmpty { listOf("Nothing changed.") }
    }

    private fun changes(was: RouterConfig, now: RouterConfig, after: RoutingConfig): List<String> {
        val lines = mutableListOf<String>()
        val name = now.name
        if (was.description != now.description) lines += if (now.description == null) "'$name' has no description now." else "'$name' is described as \"${now.description}\" now."
        if (was.confirm != now.confirm) lines += if (now.confirm) "'$name' now asks you first." else "'$name' no longer asks you first."
        val sharesWas = was.shares
        val sharesNow = now.shares
        if (sharesWas != null && sharesNow != null && sharesWas != sharesNow) {
            lines += "'$name' shares differently: location ${grantName(sharesNow.location)}, recording time ${grantName(sharesNow.recordedAt)}."
        }
        when {
            was is RouterConfig.Switch && now is RouterConfig.Switch -> {
                if (was.rules != now.rules) lines += rules(name, was.rules.size, now.rules.size)
                if (was.otherwise != now.otherwise) lines += fallback(name, now.otherwise, after)
            }
            was is RouterConfig.Time && now is RouterConfig.Time -> {
                if (was.rules != now.rules) lines += rules(name, was.rules.size, now.rules.size)
                if (was.otherwise != now.otherwise) lines += fallback(name, now.otherwise, after)
            }
            was is RouterConfig.Place && now is RouterConfig.Place -> {
                if (was.rules != now.rules) lines += rules(name, was.rules.size, now.rules.size)
                val spotsWas = was.rules.associate { it.spot.label to it.spot }
                now.rules.forEach { rule ->
                    val old = spotsWas[rule.spot.label]
                    if (old != null && (old.latitude != rule.spot.latitude || old.longitude != rule.spot.longitude)) lines += "The place '${rule.spot.label}' of '$name' moved."
                }
                if (was.otherwise != now.otherwise) lines += fallback(name, now.otherwise, after)
                if (was.unknown != now.unknown) lines += "'$name' sends recordings without a location to ${nameOf(after, now.unknown)}."
            }
            was is RouterConfig.OpenApp && now is RouterConfig.OpenApp -> {
                if (was.rules != now.rules) lines += rules(name, was.rules.size, now.rules.size)
                if (was.otherwise != now.otherwise) lines += fallback(name, now.otherwise, after)
            }
            was is RouterConfig.WatchApp && now is RouterConfig.WatchApp -> {
                if (was.app != now.app || was.router != now.router) lines += "'$name' now sends to another watch app router."
                if (was.fields != now.fields) lines += "'$name' fills its parameters differently."
            }
            was is RouterConfig.Network && now is RouterConfig.Network -> {
                if (was.url != now.url) lines += "'$name' now posts to ${RoutingEdits.hostOf(now.url)}."
                when {
                    was.bearerToken == now.bearerToken -> Unit
                    now.bearerToken == null -> lines += "'$name' lost its token."
                    was.bearerToken == null -> lines += "'$name' has a token now."
                    else -> lines += "'$name' has a different token."
                }
                if (was.fields != now.fields) lines += "'$name' fills its parameters differently."
            }
            was is RouterConfig.App && now is RouterConfig.App -> {
                if (was.packageName != now.packageName || was.receiverClass != now.receiverClass) lines += "'$name' now sends to ${now.packageName}."
                if (was.fields != now.fields) lines += "'$name' fills its parameters differently."
            }
            else -> Unit
        }
        return lines
    }

    private fun rules(name: String, before: Int, after: Int): String =
        if (before == after) "The rules of '$name' changed." else "'$name' has $after rules instead of $before."

    private fun fallback(name: String, ref: RouterRef?, config: RoutingConfig): String =
        if (ref == null) "'$name' has no fallback now." else "'$name' falls back to ${nameOf(config, ref)} now."

    private fun nameOf(config: RoutingConfig, ref: RouterRef?): String = ref?.let { config.router(it)?.name?.let { n -> "'$n'" } ?: "a router that does not exist" } ?: "nothing"

    private fun grantName(grant: Grant): String = when (grant) {
        Grant.ASK -> "ask"
        Grant.ALWAYS -> "always"
        Grant.NEVER -> "never"
    }
}
