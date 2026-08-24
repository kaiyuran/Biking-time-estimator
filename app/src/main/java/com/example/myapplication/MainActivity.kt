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

import okhttp3.OkHttpClient
import okhttp3.Request

import org.json.JSONObject
import java.io.File
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

import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var map: MapLibreMap? = null
    private var mapReady = false

    // Points selected by the user
    private val routePoints = mutableListOf<LatLng>()

    // Markers displayed on the map
    private val markers = mutableListOf<Marker>()

    // HTTP client
    private val httpClient = OkHttpClient()

    // Background thread for API requests
    private val executor = Executors.newSingleThreadExecutor()

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val allowed =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (allowed) {
                getUserLocation()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        setContent {

            MyApplicationTheme {

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {

                    AndroidView(

                        factory = { context ->

                            mapView = MapView(context)

                            mapView.onCreate(null)

                            mapView.getMapAsync { mapInstance ->

                                map = mapInstance
                                mapReady = true

                                // Toronto starting position
                                mapInstance.cameraPosition =
                                    CameraPosition.Builder()
                                        .target(
                                            LatLng(
                                                43.6532,
                                                -79.3832
                                            )
                                        )
                                        .zoom(12.0)
                                        .build()

                                // OpenFreeMap
                                mapInstance.setStyle(

                                    Style.Builder()
                                        .fromUri(
                                            "https://tiles.openfreemap.org/styles/liberty"
                                        )

                                ) {

                                    Log.d(
                                        "MAP_TEST",
                                        "MAP READY"
                                    )

                                    setupMapClickListener()

                                    requestLocationPermission()
                                }
                            }

                            mapView
                        },

                        modifier = Modifier.fillMaxSize()
                    )

                    // Controls
                    Column(

                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),

                        verticalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        Button(
                            onClick = {

                                map?.animateCamera(
                                    CameraUpdateFactory.zoomIn()
                                )
                            }
                        ) {
                            Text("+")
                        }

                        Button(
                            onClick = {

                                map?.animateCamera(
                                    CameraUpdateFactory.zoomOut()
                                )
                            }
                        ) {
                            Text("−")
                        }

                        Button(
                            onClick = {
                                getUserLocation()
                            }
                        ) {
                            Text("📍")
                        }

                        Button(
                            onClick = {
                                clearRoute()
                            }
                        ) {
                            Text("Clear")
                        }
                        Button(
                            onClick = {
                                requestBikeRoute()
                            },
                            enabled = true,

                        ) {
                            Text("Calculate Route")
                        }
                    }
                }
            }
        }
    }

    // =========================================================
    // MAP CLICK
    // =========================================================

    private fun setupMapClickListener() {

        map?.addOnMapClickListener { point ->

            addRoutePoint(point)

            true
        }
    }

    // =========================================================
    // ADD POINT
    // =========================================================

    private fun addRoutePoint(point: LatLng) {

        /*
         * GraphHopper free plan currently supports
         * up to 5 locations per routing request.
         */

        if (routePoints.size >= 5) {

            Log.d(
                "ROUTE",
                "Maximum of 5 points for current free plan request"
            )

            return
        }

        routePoints.add(point)

        val currentMap = map ?: return

        val pointNumber = routePoints.size

        val marker = currentMap.addMarker(

            MarkerOptions()
                .position(point)
                .title("Point $pointNumber")
        )

        if (marker != null) {
            markers.add(marker)
        }

        Log.d(
            "ROUTE",
            "Added point $pointNumber: " +
                    "${point.latitude}, ${point.longitude}"
        )

    }

    // =========================================================
    // GRAPH HOPPER ROUTING
    // =========================================================

    private fun requestBikeRoute() {

        Log.d(
            "ROUTE",
            "GRAPH_HOPPER_API_KEY present: ${BuildConfig.GRAPH_HOPPER_API_KEY.isNotBlank()}"
        )

        if (routePoints.size < 2) {
            return
        }

        val apiKey =
            BuildConfig.GRAPH_HOPPER_API_KEY

        if (apiKey.isBlank()) {

            Log.e(
                "ROUTE",
                "GraphHopper API key is missing"
            )

            return
        }

        // Build URL
        val urlBuilder = StringBuilder(

            "https://graphhopper.com/api/1/route"
        )

        urlBuilder.append(
            "?profile=bike"
        )

        urlBuilder.append(
            "&points_encoded=false"
        )

        urlBuilder.append(
            "&elevation=true"
        )

        urlBuilder.append(
            "&instructions=false"
        )

        urlBuilder.append(
            "&key="
        )

        urlBuilder.append(apiKey)

        // Add every selected point
        for (point in routePoints) {

            urlBuilder.append(
                "&point="
            )

            urlBuilder.append(
                point.latitude
            )

            urlBuilder.append(",")

            urlBuilder.append(
                point.longitude
            )
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .build()

        executor.execute {

            try {

                Log.d(
                    "ROUTE",
                    "Requesting bicycle route..."
                )

                val response =
                    httpClient.newCall(request).execute()

                val body = response.body?.string()

                if (body != null) {
                    saveRouteToFile(body)
                }

                if (!response.isSuccessful) {

                    Log.e(
                        "ROUTE",
                        "GraphHopper error: " +
                                response.code +
                                " " +
                                body
                    )

                    return@execute
                }

                if (body == null) {

                    Log.e(
                        "ROUTE",
                        "Empty GraphHopper response"
                    )

                    return@execute
                }

                Log.d(
                    "ROUTE",
                    "Route received"
                )

                parseRoute(body)

            } catch (e: Exception) {

                Log.e(
                    "ROUTE",
                    "Routing request failed",
                    e
                )
            }
        }
    }

    // =========================================================
    // PARSE ROUTE
    // =========================================================

    private fun parseRoute(jsonString: String) {

        try {

            val json =
                JSONObject(jsonString)

            val paths =
                json.getJSONArray("paths")

            if (paths.length() == 0) {

                Log.e(
                    "ROUTE",
                    "No route returned"
                )

                return
            }

            val path =
                paths.getJSONObject(0)

            val distance =
                path.getDouble("distance")

            val time =
                path.getLong("time")

            val points =
                path.getJSONObject("points")

            val coordinates =
                points.getJSONArray("coordinates")

            val routeCoordinates =
                mutableListOf<LatLng>()

            for (i in 0 until coordinates.length()) {

                val coordinate =
                    coordinates.getJSONArray(i)

                // GeoJSON:
                // [longitude, latitude]

                val longitude =
                    coordinate.getDouble(0)

                val latitude =
                    coordinate.getDouble(1)

                routeCoordinates.add(
                    LatLng(
                        latitude,
                        longitude
                    )
                )
            }

            Log.d(
                "ROUTE",
                "Distance: ${distance / 1000.0} km"
            )

            Log.d(
                "ROUTE",
                "Time: ${time / 60000.0} minutes"
            )

            runOnUiThread {

                drawBikeRoute(
                    routeCoordinates
                )
            }

        } catch (e: Exception) {

            Log.e(
                "ROUTE",
                "Failed to parse route",
                e
            )
        }
    }



    private fun saveRouteToFile(routeJson: String) {

        try {
            val file = File(filesDir, "route_debug.txt")

            file.writeText(routeJson)

            Log.d(
                "ROUTE_FILE",
                "Route saved to: ${file.absolutePath}"
            )

        } catch (e: Exception) {

            Log.e(
                "ROUTE_FILE",
                "Failed to save route",
                e
            )
        }
    }



    // =========================================================
    // DRAW ACTUAL BIKE ROUTE
    // =========================================================

    private fun drawBikeRoute(
        coordinates: List<LatLng>
    ) {

        val currentMap =
            map ?: return

        val style =
            currentMap.style ?: return

        if (coordinates.isEmpty()) {
            return
        }

        /*
         * Convert the GraphHopper coordinates
         * into a GeoJSON LineString.
         */

        val coordinateArray =
            org.json.JSONArray()

        for (point in coordinates) {

            val coordinate =
                org.json.JSONArray()

            coordinate.put(
                point.longitude
            )

            coordinate.put(
                point.latitude
            )

            coordinateArray.put(
                coordinate
            )
        }

        val geometry =
            JSONObject()

        geometry.put(
            "type",
            "LineString"
        )

        geometry.put(
            "coordinates",
            coordinateArray
        )

        val feature =
            JSONObject()

        feature.put(
            "type",
            "Feature"
        )

        feature.put(
            "geometry",
            geometry
        )

        val existingSource =
            style.getSource(
                "bike-route-source"
            )

        if (existingSource != null) {

            val source =
                existingSource as GeoJsonSource

            source.setGeoJson(
                feature.toString()
            )

        } else {

            val source =
                GeoJsonSource(
                    "bike-route-source",
                    feature.toString()
                )

            style.addSource(source)

            val lineLayer =
                LineLayer(
                    "bike-route-layer",
                    "bike-route-source"
                )

            lineLayer.setProperties(

                PropertyFactory.lineColor(
                    "#1976D2"
                ),

                PropertyFactory.lineWidth(
                    6f
                ),

                PropertyFactory.lineOpacity(
                    0.9f
                )
            )

            style.addLayer(lineLayer)
        }
    }

    // =========================================================
    // CLEAR
    // =========================================================

    private fun clearRoute() {

        routePoints.clear()

        for (marker in markers) {
            marker.remove()
        }

        markers.clear()

        val currentMap =
            map ?: return

        val style =
            currentMap.style ?: return

        if (
            style.getLayer(
                "bike-route-layer"
            ) != null
        ) {

            style.removeLayer(
                "bike-route-layer"
            )
        }

        if (
            style.getSource(
                "bike-route-source"
            ) != null
        ) {

            style.removeSource(
                "bike-route-source"
            )
        }

        Log.d(
            "ROUTE",
            "Route cleared"
        )
    }

    // =========================================================
    // LOCATION PERMISSION
    // =========================================================

    private fun requestLocationPermission() {

        val fine =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

        val coarse =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

        if (
            fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
        ) {

            getUserLocation()

        } else {

            locationPermissionLauncher.launch(

                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // =========================================================
    // USER LOCATION
    // =========================================================

    private fun getUserLocation() {

        if (!mapReady) {
            return
        }

        val fine =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

        val coarse =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

        if (
            fine != PackageManager.PERMISSION_GRANTED &&
            coarse != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->

                if (location == null) {

                    Log.d(
                        "LOCATION",
                        "No location available"
                    )

                    return@addOnSuccessListener
                }

                val userLocation =
                    LatLng(
                        location.latitude,
                        location.longitude
                    )

                map?.animateCamera(

                    CameraUpdateFactory
                        .newLatLngZoom(
                            userLocation,
                            15.0
                        )
                )

                Log.d(
                    "LOCATION",
                    "User: " +
                            "${location.latitude}, " +
                            "${location.longitude}"
                )
            }
    }

    // =========================================================
    // MAP LIFECYCLE
    // =========================================================

    override fun onStart() {

        super.onStart()

        if (::mapView.isInitialized) {
            mapView.onStart()
        }
    }

    override fun onResume() {

        super.onResume()

        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {

        if (::mapView.isInitialized) {
            mapView.onPause()
        }

        super.onPause()
    }

    override fun onStop() {

        if (::mapView.isInitialized) {
            mapView.onStop()
        }

        super.onStop()
    }

    override fun onDestroy() {

        executor.shutdown()

        if (::mapView.isInitialized) {
            mapView.onDestroy()
        }

        super.onDestroy()
    }
}