package com.anopticlabs.gravel.notebook.routers

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceMatcherTest {

    private val home = Spot("Home", 52.5200, 13.4050, 800)

    @Test
    fun distancesMatchKnownOnesToWithinAFractionOfAPercent() {
        // One degree of latitude is about 111.2 km everywhere.
        assertTrue(abs(PlaceMatcher.distanceMeters(0.0, 0.0, 1.0, 0.0) - 111_195.0) < 300)
        // Berlin to Paris, about 878 km.
        assertTrue(abs(PlaceMatcher.distanceMeters(52.5200, 13.4050, 48.8566, 2.3522) - 877_500.0) < 3_000)
        assertEquals(0.0, PlaceMatcher.distanceMeters(52.52, 13.405, 52.52, 13.405))
        // Across the antimeridian the short way round counts.
        assertTrue(PlaceMatcher.distanceMeters(0.0, 179.9, 0.0, -179.9) < 25_000)
        // The poles are antipodal to each other, half the circumference apart.
        assertTrue(abs(PlaceMatcher.distanceMeters(90.0, 0.0, -90.0, 0.0) - 20_015_000.0) < 10_000)
    }

    @Test
    fun aRuleHoldsWithinTheCircleOrOutsideItAsItSays() {
        val near = RoutedLocation(52.5230, 13.4100, 20f)
        val far = RoutedLocation(52.4000, 13.4050)
        assertTrue(PlaceMatcher.within(home, near))
        assertFalse(PlaceMatcher.within(home, far))
        assertTrue(PlaceMatcher.holds(PlaceRule(home, within = true, RouterRef.gravel("x")), near))
        assertFalse(PlaceMatcher.holds(PlaceRule(home, within = true, RouterRef.gravel("x")), far))
        assertFalse(PlaceMatcher.holds(PlaceRule(home, within = false, RouterRef.gravel("x")), near))
        assertTrue(PlaceMatcher.holds(PlaceRule(home, within = false, RouterRef.gravel("x")), far))
        // The boundary is in: a point just under 800 m due north (one degree of latitude is 111 195.08 m here).
        val edge = RoutedLocation(52.5200 + 799.99 / 111_195.08, 13.4050)
        assertTrue(PlaceMatcher.within(home, edge))
        assertFalse(PlaceMatcher.within(home.copy(radiusMeters = 799), edge))
    }

    @Test
    fun aSpotNeedsCoordinatesOnTheGlobeAndABoundedRadius() {
        assertTrue(PlaceMatcher.isWellFormed(home))
        assertFalse(PlaceMatcher.isWellFormed(home.copy(latitude = 90.5)))
        assertFalse(PlaceMatcher.isWellFormed(home.copy(longitude = -180.5)))
        assertFalse(PlaceMatcher.isWellFormed(home.copy(latitude = Double.NaN)))
        assertFalse(PlaceMatcher.isWellFormed(home.copy(radiusMeters = 9)))
        assertFalse(PlaceMatcher.isWellFormed(home.copy(radiusMeters = 100_001)))
        assertTrue(PlaceMatcher.isWellFormed(home.copy(radiusMeters = 100_000)))
    }
}
