package com.example.myapplication

import kotlin.math.*

data class ElevationPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double
)

data class RouteSegment(
    val distanceFromStart: Double,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val elevationChange: Double,
    val gradePercent: Double
)

object RouteElevationProcessor {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Takes GraphHopper route points and creates a point every interval.
     *
     * Elevation is linearly interpolated between the surrounding
     * GraphHopper points.
     */
    fun interpolate(
        points: List<ElevationPoint>,
        intervalMeters: Double = 50.0
    ): List<RouteSegment> {

        if (points.size < 2) {
            return emptyList()
        }

        if (!intervalMeters.isFinite() || intervalMeters <= 0.0) {
            return emptyList()
        }

        if (points.any {
                !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                    !it.longitude.isFinite() || it.longitude !in -180.0..180.0 ||
                    !it.elevation.isFinite()
            }) return emptyList()

        // ---------------------------------------------------------
        // Calculate cumulative distance along the original route
        // ---------------------------------------------------------

        val cumulativeDistances = MutableList(points.size) { 0.0 }

        for (i in 1 until points.size) {

            val distance = distanceBetween(
                points[i - 1].latitude,
                points[i - 1].longitude,
                points[i].latitude,
                points[i].longitude
            )

            cumulativeDistances[i] =
                cumulativeDistances[i - 1] + distance
        }

        val totalDistance =
            cumulativeDistances.last()

        if (totalDistance <= 0.0) {
            return emptyList()
        }

        // ---------------------------------------------------------
        // Create points every 50m
        // ---------------------------------------------------------

        val result = mutableListOf<RouteSegment>()

        var targetDistance = 0.0

        var originalIndex = 0

        var previousElevation: Double? = null

        while (targetDistance <= totalDistance) {

            // Find the two original points surrounding targetDistance
            while (
                originalIndex < cumulativeDistances.size - 2 &&
                cumulativeDistances[originalIndex + 1] < targetDistance
            ) {
                originalIndex++
            }

            val index1 = originalIndex
            val index2 = min(
                originalIndex + 1,
                points.size - 1
            )

            val point1 = points[index1]
            val point2 = points[index2]

            val distance1 =
                cumulativeDistances[index1]

            val distance2 =
                cumulativeDistances[index2]

            val segmentDistance =
                distance2 - distance1

            val fraction =
                if (segmentDistance > 0.0) {
                    ((targetDistance - distance1) / segmentDistance)
                        .coerceIn(0.0, 1.0)
                } else {
                    0.0
                }

            // -----------------------------------------------------
            // Interpolate latitude
            // -----------------------------------------------------

            val latitude =
                point1.latitude +
                        (point2.latitude - point1.latitude) * fraction

            // -----------------------------------------------------
            // Interpolate longitude
            // -----------------------------------------------------

            val longitude =
                point1.longitude +
                        (((point2.longitude - point1.longitude + 540.0) % 360.0) - 180.0) * fraction

            // -----------------------------------------------------
            // Interpolate elevation
            // -----------------------------------------------------

            val elevation =
                point1.elevation +
                        (point2.elevation - point1.elevation) * fraction

            // -----------------------------------------------------
            // Calculate elevation change
            // -----------------------------------------------------

            val elevationChange =
                if (previousElevation != null) {
                    elevation - previousElevation
                } else {
                    0.0
                }

            // -----------------------------------------------------
            // Calculate grade
            //
            // grade = elevation change / horizontal distance
            // -----------------------------------------------------

            val gradePercent =
                if (previousElevation != null && intervalMeters > 0.0) {
                    (elevationChange / intervalMeters) * 100.0
                } else {
                    0.0
                }

            result.add(
                RouteSegment(
                    distanceFromStart = targetDistance,
                    latitude = latitude,
                    longitude = ((longitude + 540.0) % 360.0) - 180.0,
                    elevation = elevation,
                    elevationChange = elevationChange,
                    gradePercent = gradePercent
                )
            )

            previousElevation = elevation

            val nextDistance = targetDistance + intervalMeters
            if (nextDistance <= targetDistance) break
            targetDistance = nextDistance
        }

        // ---------------------------------------------------------
        // Always include the final point
        // ---------------------------------------------------------

        if (
            result.isEmpty() ||
            result.last().distanceFromStart < totalDistance
        ) {

            val finalPoint = points.last()

            val elevationChange =
                if (previousElevation != null) {
                    finalPoint.elevation - previousElevation
                } else {
                    0.0
                }

            val finalDistanceFromPrevious =
                if (result.isNotEmpty()) {
                    totalDistance -
                            result.last().distanceFromStart
                } else {
                    totalDistance
                }

            val gradePercent =
                if (finalDistanceFromPrevious > 0.0) {
                    (elevationChange /
                            finalDistanceFromPrevious) * 100.0
                } else {
                    0.0
                }

            result.add(
                RouteSegment(
                    distanceFromStart = totalDistance,
                    latitude = finalPoint.latitude,
                    longitude = finalPoint.longitude,
                    elevation = finalPoint.elevation,
                    elevationChange = elevationChange,
                    gradePercent = gradePercent
                )
            )
        }

        return result
    }

    /**
     * Haversine distance between two GPS coordinates.
     */
    private fun distanceBetween(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val deltaLat =
            Math.toRadians(lat2 - lat1)

        val deltaLon =
            Math.toRadians(lon2 - lon1)

        val a =
            sin(deltaLat / 2).pow(2) +
                    cos(lat1Rad) *
                    cos(lat2Rad) *
                    sin(deltaLon / 2).pow(2)

        val c =
            2 * atan2(
                sqrt(a.coerceIn(0.0, 1.0)),
                sqrt(1 - a.coerceIn(0.0, 1.0))
            )

        return EARTH_RADIUS_METERS * c
    }
}
