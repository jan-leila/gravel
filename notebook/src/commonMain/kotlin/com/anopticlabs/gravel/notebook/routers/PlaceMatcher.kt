package com.anopticlabs.gravel.notebook.routers

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Decides [PlaceRule]s against where a recording was made. */
object PlaceMatcher {
    const val MIN_RADIUS_METERS = 10
    const val MAX_RADIUS_METERS = 100_000

    /** The mean radius of the Earth, which puts the haversine distance within a third of a percent anywhere. */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun isWellFormed(spot: Spot): Boolean =
        spot.latitude.isFinite() && spot.latitude in -90.0..90.0 &&
            spot.longitude.isFinite() && spot.longitude in -180.0..180.0 &&
            spot.radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS

    /** The great-circle distance between two points, by the haversine formula. */
    fun distanceMeters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val phi1 = latitude1.toRadians()
        val phi2 = latitude2.toRadians()
        val dPhi = (latitude2 - latitude1).toRadians()
        val dLambda = (longitude2 - longitude1).toRadians()
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun within(spot: Spot, location: RoutedLocation): Boolean =
        distanceMeters(spot.latitude, spot.longitude, location.latitude, location.longitude) <= spot.radiusMeters

    fun holds(rule: PlaceRule, location: RoutedLocation): Boolean = within(rule.spot, location) == rule.within

    private fun Double.toRadians(): Double = this * kotlin.math.PI / 180.0
}
