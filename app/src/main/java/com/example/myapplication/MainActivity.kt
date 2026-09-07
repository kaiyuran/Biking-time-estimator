package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var map: MapLibreMap? = null
    private var mapReady = false

    private var elevationProfile by mutableStateOf<List<RouteSegment>>(emptyList())
    private var providerMinutes by mutableStateOf<Double?>(null)
    private var routeStatus by mutableStateOf("Select at least two points, then calculate a route.")
    private var baseSpeed by mutableStateOf(18f)
    private var gradeWeight by mutableStateOf(30f)
    private var windWeight by mutableStateOf(1f)
    private var routeWind by mutableStateOf<RouteWind?>(null)
    private var windStatus by mutableStateOf("Wind loads when you calculate a route.")
    private var requestVersion = 0

    private val routePoints = mutableListOf<LatLng>()

    // Distance of segments(m)
    private var segmentDistanceMeters = 100.0

    private val markers = mutableListOf<Marker>()
    private val httpClient = OkHttpClient()

    // Background thread for API requests
    private val executor = Executors.newSingleThreadExecutor()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allowed = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (allowed) getUserLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            mapView = MapView(context).apply {
                                onCreate(null)
                                getMapAsync { mapInstance ->
                                    map = mapInstance
                                    mapReady = true
                                    mapInstance.cameraPosition = CameraPosition.Builder()
                                        .target(LatLng(43.6532, -79.3832))
                                        .zoom(12.0)
                                        .build()

                                    mapInstance.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) {
                                        Log.d("MAP_TEST", "MAP READY")
                                        setupMapClickListener()
                                        requestLocationPermission()
                                    }
                                }
                            }
                            mapView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    Card(
                        modifier = Modifier.align(Alignment.TopCenter)
                            .statusBarsPadding().padding(12.dp).fillMaxWidth()
                    ) {
                        val estimate = routeWind?.let { wind ->
                            if (elevationProfile.size >= 2) BikeTimeEstimator.estimate(
                                elevationProfile, wind, baseSpeed.toDouble() / 3.6,
                                gradeWeight.toDouble(), windWeight.toDouble()
                            ) else null
                        }
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 330.dp).padding(12.dp)
                        ) {
                            item {
                                Text(routeStatus)
                                providerMinutes?.let { Text("GraphHopper: %.1f min".format(it)) }
                                Text(windStatus)
                                estimate?.let {
                                    Text(if (it.minutes.isFinite()) "Estimated bike time: %.1f min".format(it.minutes)
                                        else "Cannot complete: calculated speed is zero or negative on a segment.")
                                }
                                Text("V = V0 cos(atan G) - Kg sin(atan G) - Kw W cos(wind - heading)")
                                Text("V0 (flat, calm speed): %.1f km/h".format(baseSpeed))
                                Slider(value = baseSpeed, onValueChange = { baseSpeed = it }, valueRange = 3f..45f)
                                Text("Kg (grade weight): %.1f m/s".format(gradeWeight))
                                Slider(value = gradeWeight, onValueChange = { gradeWeight = it }, valueRange = 0f..100f)
                                Text("Kw (wind weight): %.2f".format(windWeight))
                                Slider(value = windWeight, onValueChange = { windWeight = it }, valueRange = 0f..3f)
                                if (elevationProfile.isNotEmpty()) Text("Route samples (every 100 m + endpoint)")
                            }
                            itemsIndexed(elevationProfile) { index, point ->
                                Text("Point %d | %.0f m along route | height %.1f m | grade %.1f%%".format(
                                    index + 1, point.distanceFromStart, point.elevation, point.gradePercent
                                ))
                                estimate?.segments?.getOrNull(index - 1)?.let { segment ->
                                    Text("Segment %d: grade %.2f%% | heading %.0f? | speed %.1f km/h | %s".format(
                                        index, segment.grade * 100, segment.bearingDegrees,
                                        segment.speedMetersPerSecond * 3.6,
                                        if (segment.seconds.isFinite()) "%.1f sec".format(segment.seconds) else "impassable"
                                    ))
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }) { Text("+") }
                        Button(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }) { Text("−") }
                        Button(onClick = { getUserLocation() }) { Text("📍") }
                        Button(onClick = { clearRoute() }) { Text("Clear") }
                        Button(onClick = { requestBikeRoute() }) { Text("Calculate Route") }
                    }
                }
            }
        }
    }

    private fun setupMapClickListener() {
        map?.addOnMapClickListener { point ->
            addRoutePoint(point)
            true
        }
    }

    private fun addRoutePoint(point: LatLng) {
        if (routePoints.size >= 5) {
            Log.d("ROUTE", "Maximum of 5 points for current free plan request")
            return
        }

        requestVersion++
        routeWind = null
        windStatus = "Wind loads when you calculate a route."
        elevationProfile = emptyList()
        providerMinutes = null
        routeStatus = "Points changed; calculate route to get heights and time."
        routePoints.add(point)
        val currentMap = map ?: return
        val marker = currentMap.addMarker(
            MarkerOptions()
                .position(point)
                .title("Point ${routePoints.size}")
        )
        if (marker != null) markers.add(marker)
        Log.d("ROUTE", "Added point ${routePoints.size}: ${point.latitude}, ${point.longitude}")
    }

    private fun requestBikeRoute() {
        Log.d("ROUTE", "GRAPH_HOPPER_API_KEY present: ${BuildConfig.GRAPH_HOPPER_API_KEY.isNotBlank()}")
        if (routePoints.size < 2) {
            routeStatus = "Select at least two points."
            return
        }

        val apiKey = BuildConfig.GRAPH_HOPPER_API_KEY
        if (apiKey.isBlank()) {
            routeStatus = "GraphHopper API key is missing."
            Log.e("ROUTE", "GraphHopper API key is missing")
            return
        }

        val urlBuilder = StringBuilder("https://graphhopper.com/api/1/route?profile=bike&points_encoded=false&elevation=true&instructions=false&key=$apiKey")
        for (point in routePoints) {
            urlBuilder.append("&point=${point.latitude},${point.longitude}")
        }

        val version = ++requestVersion
        routeWind = null
        windStatus = "Wind loads when you calculate a route."
        elevationProfile = emptyList()
        providerMinutes = null
        routeStatus = "Calculating route..."
        val request = Request.Builder().url(urlBuilder.toString()).get().build()
        executor.execute {
            try {
                Log.d("ROUTE", "Requesting bicycle route...")
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (body != null) saveRouteToFile(body)

                if (!response.isSuccessful || body == null) {
                    Log.e("ROUTE", "GraphHopper error: ${response.code} $body")
                    runOnUiThread {
                        if (version == requestVersion) routeStatus = "Route request failed (${response.code})."
                    }
                    return@execute
                }

                Log.d("ROUTE", "Route received")
                parseRoute(body, version)
            } catch (e: Exception) {
                Log.e("ROUTE", "Routing request failed", e)
                runOnUiThread {
                    if (version == requestVersion) routeStatus = "Route request failed; try again."
                }
            }
        }
    }

    private fun parseRoute(jsonString: String, version: Int) {
        try {
            val json = JSONObject(jsonString)
            val paths = json.getJSONArray("paths")
            if (paths.length() == 0) {
                runOnUiThread {
                    if (version == requestVersion) routeStatus = "No route returned."
                }
                Log.e("ROUTE", "No route returned")
                return
            }

            val path = paths.getJSONObject(0)
            val distance = path.getDouble("distance")
            val time = path.getLong("time")
            val coordinates = path.getJSONObject("points").getJSONArray("coordinates")
            val routeCoordinates = mutableListOf<LatLng>()
            val elevationPoints = mutableListOf<ElevationPoint>()

            for (i in 0 until coordinates.length()) {
                val coordinate = coordinates.getJSONArray(i)
                routeCoordinates.add(LatLng(coordinate.getDouble(1), coordinate.getDouble(0)))
                val elevation = coordinate.optDouble(2, Double.NaN)
                if (elevation.isFinite()) {
                    elevationPoints.add(ElevationPoint(
                        coordinate.getDouble(1), coordinate.getDouble(0), elevation
                    ))
                }
            }

            var profile = emptyList<RouteSegment>()
            // Missing elevations must not become sea level or bridge gaps in the profile.
            if (elevationPoints.size == routeCoordinates.size) {
                profile = RouteElevationProcessor.interpolate(elevationPoints, segmentDistanceMeters)
                for (sample in profile) {
                    Log.d("ELEVATION", "Distance: ${sample.distanceFromStart} m, " +
                        "elevation: ${sample.elevation} m, change: ${sample.elevationChange} m, " +
                        "grade: ${sample.gradePercent}%")
                }
            } else {
                Log.w("ELEVATION", "Route elevation is missing or invalid; profile unavailable")
            }

            Log.d("ROUTE", "Distance: ${distance / 1000.0} km")
            Log.d("ROUTE", "Time: ${time / 60000.0} minutes")
            runOnUiThread {
                if (version != requestVersion || isDestroyed) return@runOnUiThread
                elevationProfile = profile
                providerMinutes = time / 60000.0
                routeStatus = if (profile.isEmpty()) "Elevation unavailable; custom estimate unavailable."
                    else "Route: %.2f km. Heights in metres above sea level.".format(distance / 1000.0)
                drawBikeRoute(routeCoordinates)
            }
            if (profile.size >= 2) fetchRouteWind(profile.first(), version)
        } catch (e: Exception) {
            Log.e("ROUTE", "Failed to parse route", e)
            runOnUiThread {
                if (version == requestVersion) routeStatus = "Could not read route data."
            }
        }
    }

    // One current observation at the route start is used for all segments.
    // Each segment still has its own grade and travel bearing.
    private fun fetchRouteWind(start: RouteSegment, version: Int) {
        val key = BuildConfig.OPEN_WEATHER_MAP_API_KEY
        fun publish(wind: RouteWind?, status: String) = runOnUiThread {
            if (version == requestVersion && !isDestroyed) {
                routeWind = wind
                windStatus = status
            }
        }
        if (key.isBlank()) {
            publish(null, "Add OPEN_WEATHER_MAP_API_KEY to local.properties and rebuild to enable wind estimates.")
            return
        }
        publish(null, "Loading OpenWeatherMap wind at route start...")
        try {
            val url = "https://api.openweathermap.org/data/2.5/weather".toHttpUrl().newBuilder()
                .addQueryParameter("lat", start.latitude.toString())
                .addQueryParameter("lon", start.longitude.toString())
                .addQueryParameter("appid", key)
                .addQueryParameter("units", "metric").build()
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    publish(null, "Wind unavailable (HTTP ${response.code}); calculate route to retry.")
                    return
                }
                val data = JSONObject(response.body?.string() ?: error("Empty weather response"))
                val windJson = data.getJSONObject("wind")
                val speed = windJson.getDouble("speed")
                val wind = RouteWind(speed, if (speed == 0.0) 0.0 else windJson.getDouble("deg"))
                publish(wind, "OpenWeatherMap: %.1f m/s from %.0f? at route start; used for all segments.".format(
                    wind.speedMetersPerSecond, wind.fromDegrees))
            }
        } catch (_: Exception) {
            // Do not log request URLs: they contain the API key.
            publish(null, "Wind unavailable; calculate route to retry. Custom estimate requires wind data.")
        }
    }

    private fun saveRouteToFile(routeJson: String) {
        try {
            val file = File(filesDir, "route_debug.txt")
            file.writeText(routeJson)
            Log.d("ROUTE_FILE", "Route saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ROUTE_FILE", "Failed to save route", e)
        }
    }

    private fun drawBikeRoute(coordinates: List<LatLng>) {

        val currentMap = map ?: return
        val style = currentMap.style ?: return

        if (coordinates.size < 2) return

        // Split route into alternating segments
        val segments = splitRouteIntoSegments(coordinates)

        // Separate red and blue segments
        val redSegments = mutableListOf<List<LatLng>>()
        val blueSegments = mutableListOf<List<LatLng>>()

        for ((index, segment) in segments.withIndex()) {

            if (index % 2 == 0) {
                redSegments.add(segment)
            } else {
                blueSegments.add(segment)
            }
        }

        // Create GeoJSON for each color
        val redGeoJson = createMultiLineStringFeature(redSegments)
        val blueGeoJson = createMultiLineStringFeature(blueSegments)

        // Remove old route layers/sources if they exist
        if (style.getLayer("bike-route-red-layer") != null) {
            style.removeLayer("bike-route-red-layer")
        }

        if (style.getLayer("bike-route-blue-layer") != null) {
            style.removeLayer("bike-route-blue-layer")
        }

        if (style.getSource("bike-route-red-source") != null) {
            style.removeSource("bike-route-red-source")
        }

        if (style.getSource("bike-route-blue-source") != null) {
            style.removeSource("bike-route-blue-source")
        }

        // ---------------------------------------------------------
        // RED SOURCE
        // ---------------------------------------------------------

        val redSource = GeoJsonSource(
            "bike-route-red-source",
            redGeoJson.toString()
        )

        style.addSource(redSource)

        val redLayer = LineLayer(
            "bike-route-red-layer",
            "bike-route-red-source"
        ).apply {

            setProperties(

                PropertyFactory.lineColor("#F44336"),

                PropertyFactory.lineWidth(6f),

                PropertyFactory.lineOpacity(0.95f)
            )
        }

        style.addLayer(redLayer)

        // ---------------------------------------------------------
        // BLUE SOURCE
        // ---------------------------------------------------------

        val blueSource = GeoJsonSource(
            "bike-route-blue-source",
            blueGeoJson.toString()
        )

        style.addSource(blueSource)

        val blueLayer = LineLayer(
            "bike-route-blue-layer",
            "bike-route-blue-source"
        ).apply {

            setProperties(

                PropertyFactory.lineColor("#1976D2"),

                PropertyFactory.lineWidth(6f),

                PropertyFactory.lineOpacity(0.95f)
            )
        }

        style.addLayer(blueLayer)

        Log.d(
            "ROUTE",
            "Route drawn with ${segments.size} alternating ${segmentDistanceMeters}m segments"
        )
    }

    private fun splitRouteIntoSegments(
        coordinates: List<LatLng>
    ): List<List<LatLng>> {

        val segments = mutableListOf<List<LatLng>>()

        var currentSegment = mutableListOf<LatLng>()
        var currentSegmentDistance = 0.0

        currentSegment.add(coordinates.first())

        for (i in 1 until coordinates.size) {

            var start = coordinates[i - 1]
            val end = coordinates[i]

            var remainingDistance = distanceBetween(start, end)

            while (remainingDistance > 0.0) {

                val distanceNeeded =
                    segmentDistanceMeters - currentSegmentDistance

                if (remainingDistance <= distanceNeeded) {

                    currentSegment.add(end)

                    currentSegmentDistance += remainingDistance

                    remainingDistance = 0.0

                    if (currentSegmentDistance >= segmentDistanceMeters - 0.001) {

                        segments.add(currentSegment)

                        currentSegment = mutableListOf()
                        currentSegment.add(end)

                        currentSegmentDistance = 0.0
                    }

                } else {

                    val fraction =
                        distanceNeeded / remainingDistance

                    val splitPoint =
                        interpolatePoint(
                            start,
                            end,
                            fraction
                        )

                    currentSegment.add(splitPoint)

                    segments.add(currentSegment)

                    currentSegment = mutableListOf()
                    currentSegment.add(splitPoint)

                    currentSegmentDistance = 0.0

                    start = splitPoint

                    remainingDistance =
                        distanceBetween(start, end)
                }
            }
        }

        if (currentSegment.size >= 2) {
            segments.add(currentSegment)
        }

        return segments
    }

    private fun distanceBetween(a: LatLng,b: LatLng): Double {

        val earthRadius = 6371000.0

        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val deltaLat =
            Math.toRadians(b.latitude - a.latitude)

        val deltaLon =
            Math.toRadians(b.longitude - a.longitude)

        val sinLat = sin(deltaLat / 2.0)
        val sinLon = sin(deltaLon / 2.0)

        val h =
            sinLat * sinLat +
                    cos(lat1) *
                    cos(lat2) *
                    sinLon * sinLon

        val c =
            2.0 * atan2(
                sqrt(h),
                sqrt(1.0 - h)
            )

        return earthRadius * c
    }


    private fun interpolatePoint(start: LatLng,end: LatLng,fraction: Double): LatLng {

        val latitude =
            start.latitude +
                    (end.latitude - start.latitude) * fraction

        val longitude =
            start.longitude +
                    (end.longitude - start.longitude) * fraction

        return LatLng(
            latitude,
            longitude
        )
    }

    private fun createMultiLineStringFeature(segments: List<List<LatLng>>): JSONObject {

        val multiLineCoordinates =
            org.json.JSONArray()

        for (segment in segments) {

            val lineCoordinates =
                org.json.JSONArray()

            for (point in segment) {

                val coordinate =
                    org.json.JSONArray()

                coordinate.put(point.longitude)
                coordinate.put(point.latitude)

                lineCoordinates.put(coordinate)
            }

            multiLineCoordinates.put(lineCoordinates)
        }

        return JSONObject().apply {

            put("type", "Feature")

            put(
                "geometry",
                JSONObject().apply {

                    put(
                        "type",
                        "MultiLineString"
                    )

                    put(
                        "coordinates",
                        multiLineCoordinates
                    )
                }
            )
        }
    }


    private fun clearRoute() {

        requestVersion++
        routeWind = null
        windStatus = "Wind loads when you calculate a route."
        elevationProfile = emptyList()
        providerMinutes = null
        routeStatus = "Select at least two points, then calculate a route."
        routePoints.clear()

        markers.forEach {
            it.remove()
        }

        markers.clear()

        val style = map?.style ?: return

        // Remove red route
        if (style.getLayer("bike-route-red-layer") != null) {
            style.removeLayer("bike-route-red-layer")
        }

        if (style.getSource("bike-route-red-source") != null) {
            style.removeSource("bike-route-red-source")
        }

        // Remove blue route
        if (style.getLayer("bike-route-blue-layer") != null) {
            style.removeLayer("bike-route-blue-layer")
        }

        if (style.getSource("bike-route-blue-source") != null) {
            style.removeSource("bike-route-blue-source")
        }

        Log.d(
            "ROUTE",
            "Route cleared"
        )
    }

    private fun requestLocationPermission() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            getUserLocation()
        } else {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun getUserLocation() {
        if (!mapReady) return
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                Log.d("LOCATION", "No location available")
                return@addOnSuccessListener
            }
            val userLocation = LatLng(location.latitude, location.longitude)
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15.0))
            Log.d("LOCATION", "User: ${location.latitude}, ${location.longitude}")
        }
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::mapView.isInitialized) mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdown()
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }
}
