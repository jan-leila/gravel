package com.anopticlabs.gravel.notebook.routers

/**
 * A routing with its secrets taken out, and what was taken: a Network router's token becomes a
 * placeholder the text model can keep or move but not read, and a Place router's spot has its
 * coordinates replaced by a placeholder too (a home is a secret). [tokens] maps each placeholder
 * to the token it stands for; [spots] maps a spot's placeholder number to its coordinates.
 */
data class RedactedRouting(
    val config: RoutingConfig,
    val tokens: Map<String, String>,
    val spots: Map<Int, Pair<Double, Double>>,
)

/** Takes the secrets out of a routing before the text model sees it, and puts them back into what it answered. */
object RoutingSecrets {
    private const val TOKEN_PREFIX = "SECRET_"
    private val PLACEHOLDER = Regex("SECRET_\\d+")

    /**
     * A spot's placeholder is latitude 0 and its number as the longitude, so it travels with the
     * spot through a relabel or a reorder, and two spots never share one, label or no label.
     */
    fun redact(config: RoutingConfig): RedactedRouting {
        val tokens = mutableMapOf<String, String>()
        val spots = mutableMapOf<Int, Pair<Double, Double>>()
        val routers = config.routers.map { router ->
            when (router) {
                is RouterConfig.Network -> router.bearerToken?.let { token ->
                    val placeholder = "$TOKEN_PREFIX${tokens.size + 1}"
                    tokens[placeholder] = token
                    router.copy(bearerToken = placeholder)
                } ?: router
                is RouterConfig.Place -> router.copy(
                    rules = router.rules.map { rule ->
                        val number = spots.size + 1
                        spots[number] = rule.spot.latitude to rule.spot.longitude
                        rule.copy(spot = rule.spot.copy(latitude = 0.0, longitude = number.toDouble()))
                    },
                )
                else -> router
            }
        }
        return RedactedRouting(config.copy(routers = routers), tokens, spots)
    }

    /**
     * [config] as the model answered it, with every placeholder back to its secret, wherever the
     * model put it. A placeholder that was never handed out is refused: it names a secret that
     * does not exist. A token typed in full, or coordinates the model set, are kept as they are;
     * a spot at the origin has none, and is refused rather than stored at a point in the Atlantic.
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
                        val spot = rule.spot
                        val number = placeholderNumber(spot)
                        when {
                            number == null -> rule
                            number == 0 -> throw IllegalArgumentException("The place '${spot.label}' has no coordinates; set them in the settings.")
                            else -> {
                                val known = redacted.spots[number] ?: throw IllegalArgumentException("The change names a place that does not exist.")
                                rule.copy(spot = spot.copy(latitude = known.first, longitude = known.second))
                            }
                        }
                    },
                )
                else -> router
            }
        }
        return config.copy(routers = routers)
    }

    // A placeholder is latitude 0 and a small whole longitude; 0 is the origin, which no handed-out placeholder has.
    private fun placeholderNumber(spot: Spot): Int? {
        if (spot.latitude != 0.0 || spot.longitude < 0.0 || spot.longitude > RoutingConfig.MAX_ROUTERS * RoutingConfig.MAX_RULES) return null
        val number = spot.longitude.toInt()
        return if (number.toDouble() == spot.longitude) number else null
    }
}
