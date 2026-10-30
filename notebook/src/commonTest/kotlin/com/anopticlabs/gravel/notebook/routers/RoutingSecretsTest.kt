package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RoutingSecretsTest {

    private fun g(id: String) = RouterRef.gravel(id)
    private val note = RouterConfig.Note("notebook", "Notebook")
    private val hook = RouterConfig.Network("hook", "Hook", "https://example.test/a", bearerToken = "tok-a")
    private val other = RouterConfig.Network("other", "Other", "https://example.test/b", bearerToken = "tok-b")
    private val open = RouterConfig.Network("open", "Open", "https://example.test/c")
    private val home = Spot("Home", 52.52, 13.405, 800)
    private val places = RouterConfig.Place("places", "Places", listOf(PlaceRule(home, true, g("hook"))), otherwise = g("notebook"))
    private val config = RoutingConfig(entry = g("places"), routers = listOf(places, note, hook, other, open))

    @Test
    fun tokensBecomePlaceholdersAndSpotsLoseTheirCoordinates() {
        val redacted = RoutingSecrets.redact(config)
        val text = redacted.config.toString()
        assertFalse(text.contains("tok-a") || text.contains("tok-b") || text.contains("52.52") || text.contains("13.405"), text)
        assertEquals("SECRET_1", (redacted.config.router(g("hook")) as RouterConfig.Network).bearerToken)
        assertEquals("SECRET_2", (redacted.config.router(g("other")) as RouterConfig.Network).bearerToken)
        assertNull((redacted.config.router(g("open")) as RouterConfig.Network).bearerToken)
        assertEquals(mapOf("SECRET_1" to "tok-a", "SECRET_2" to "tok-b"), redacted.tokens)
        assertEquals(Spot("Home", 0.0, 0.0, 800), (redacted.config.router(g("places")) as RouterConfig.Place).rules.single().spot)
        assertEquals(mapOf("Home" to (52.52 to 13.405)), redacted.spots)
        assertEquals(config, RoutingSecrets.restore(redacted.config, redacted), "an untouched answer restores to the original")
    }

    @Test
    fun aPlaceholderIsRestoredWhereverTheModelPutItAndATypedTokenIsKept() {
        val redacted = RoutingSecrets.redact(config)
        val moved = redacted.config.copy(
            routers = redacted.config.routers.map {
                when {
                    it is RouterConfig.Network && it.id == "hook" -> it.copy(bearerToken = "SECRET_2")
                    it is RouterConfig.Network && it.id == "other" -> it.copy(bearerToken = null)
                    it is RouterConfig.Network && it.id == "open" -> it.copy(bearerToken = "typed-in-full")
                    else -> it
                }
            } + RouterConfig.Network("new", "New", "https://example.test/d", bearerToken = "SECRET_1"),
        )
        val restored = RoutingSecrets.restore(moved, redacted)
        assertEquals("tok-b", (restored.router(g("hook")) as RouterConfig.Network).bearerToken)
        assertNull((restored.router(g("other")) as RouterConfig.Network).bearerToken)
        assertEquals("typed-in-full", (restored.router(g("open")) as RouterConfig.Network).bearerToken)
        assertEquals("tok-a", (restored.router(g("new")) as RouterConfig.Network).bearerToken)
    }

    @Test
    fun anUnknownPlaceholderIsRefusedAndARenamedSpotStaysWhereTheModelLeftIt() {
        val redacted = RoutingSecrets.redact(config)
        val invented = redacted.config.copy(routers = redacted.config.routers.map { if (it is RouterConfig.Network && it.id == "open") it.copy(bearerToken = "SECRET_9") else it })
        assertEquals("The change names a secret that does not exist.", assertFailsWith<IllegalArgumentException> { RoutingSecrets.restore(invented, redacted) }.message)
        val renamed = redacted.config.copy(routers = redacted.config.routers.map { if (it is RouterConfig.Place) it.copy(rules = listOf(it.rules.single().copy(spot = it.rules.single().spot.copy(label = "House")))) else it })
        assertEquals(Spot("House", 0.0, 0.0, 800), (RoutingSecrets.restore(renamed, redacted).router(g("places")) as RouterConfig.Place).rules.single().spot)
        val moved = redacted.config.copy(routers = redacted.config.routers.map { if (it is RouterConfig.Place) it.copy(rules = listOf(it.rules.single().copy(spot = it.rules.single().spot.copy(latitude = 1.0, longitude = 2.0)))) else it })
        assertEquals(Spot("Home", 1.0, 2.0, 800), (RoutingSecrets.restore(moved, redacted).router(g("places")) as RouterConfig.Place).rules.single().spot, "coordinates the model set are its own")
    }
}
