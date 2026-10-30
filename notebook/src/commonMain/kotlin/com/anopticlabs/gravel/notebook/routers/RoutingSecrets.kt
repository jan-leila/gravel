package com.anopticlabs.gravel.notebook.routers

/**
 * A routing with its secrets taken out, and what was taken: a Network router's token becomes a
 * placeholder the text model can keep or move but not read, and a Place router's spot loses its
 * coordinates (a home is a secret too). [tokens] maps each placeholder to the token it stands for;
 * [spots] maps a spot's label to its coordinates.
 */
data class RedactedRouting(
    val config: RoutingConfig,
    val tokens: Map<String, String>,
    val spots: Map<String, Pair<Double, Double>>,
)

/** Takes the secrets out of a routing before the text model sees it, and puts them back into what it answered. */
object RoutingSecrets {
    private const val TOKEN_PREFIX = "SECRET_"
    private val PLACEHOLDER = Regex("SECRET_\\d+")

    fun redact(config: RoutingConfig): RedactedRouting {
        val tokens = mutableMapOf<String, String>()
        val spots = mutableMapOf<String, Pair<Double, Double>>()
        val routers = config.routers.map { router ->
            when (router) {
                is RouterConfig.Network -> router.bearerToken?.let { token ->
                    val placeholder = "$TOKEN_PREFIX${tokens.size + 1}"
                    tokens[placeholder] = token
                    router.copy(bearerToken = placeholder)
                } ?: router
                is RouterConfig.Place -> router.copy(
                    rules = router.rules.map { rule ->
                        spots.putIfAbsent(rule.spot.label, rule.spot.latitude to rule.spot.longitude)
                        rule.copy(spot = rule.spot.copy(latitude = 0.0, longitude = 0.0))
                    },
                )
                else -> router
            }
        }
        return RedactedRouting(config.copy(routers = routers), tokens, spots)
    }

    /**
     * [config] as the model answered it, with every placeholder back to its token, wherever the
     * model put it, and every spot still at the origin back to its coordinates by its label. A
     * placeholder that was never handed out is refused: it names a secret that does not exist. A
     * token typed in full is kept as typed.
     */
    fun restore(config: RoutingConfig, redacted: RedactedRouting): RoutingConfig {
        val routers = config.routers.map { router ->
            when (router) {
                is RouterConfig.Network -> {
                    val token = router.bearerToken
                    when {
                        token == null -> router
                        redacted.tokens.containsKey(token) -> router.copy(bearerToken = redacted.tokens.getValue(token))
                        PLACEHOLDER.matches(token) -> throw IllegalArgumentException("The change names a secret that does not exist.")
                        else -> router
                    }
                }
                is RouterConfig.Place -> router.copy(
                    rules = router.rules.map { rule ->
                        val known = redacted.spots[rule.spot.label]
                        if (known != null && rule.spot.latitude == 0.0 && rule.spot.longitude == 0.0) {
                            rule.copy(spot = rule.spot.copy(latitude = known.first, longitude = known.second))
                        } else {
                            rule
                        }
                    },
                )
                else -> router
            }
        }
        return config.copy(routers = routers)
    }
}
