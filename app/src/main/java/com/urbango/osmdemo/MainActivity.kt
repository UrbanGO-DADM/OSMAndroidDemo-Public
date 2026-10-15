package com.urbango.osmdemo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.urbango.osmdemo.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private var isDarkModeMap = false

    private val httpClient = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentRoutePolyline: Polyline? = null
    private var currentRoutePoints: List<GeoPoint> = emptyList()
    private var destinationMarker: Marker? = null

    companion object {
        private const val PERMISSION_REQUEST_LOCATION = 1001
        // Coordenadas de Bogotá D.C.
        private val BOGOTA_CENTER = GeoPoint(4.6097, -74.0817)
        private val UNAL_BOGOTA = GeoPoint(4.6382, -74.0841)
        private val PLAZA_BOLIVAR = GeoPoint(4.5981, -74.0760)
        private val PARQUE_93 = GeoPoint(4.6767, -74.0482)

        // Matriz de inversión de color para Modo Oscuro nativo (sin requerir API Keys ni servidores externos)
        private val DARK_COLOR_MATRIX = ColorMatrix(floatArrayOf(
            -0.85f,  0.0f,   0.0f,   0.0f, 240.0f,
             0.0f,  -0.85f,  0.0f,   0.0f, 240.0f,
             0.0f,   0.0f,  -0.80f,  0.0f, 245.0f,
             0.0f,   0.0f,   0.0f,   1.0f,   0.0f
        ))
        private val DARK_MODE_FILTER = ColorMatrixColorFilter(DARK_COLOR_MATRIX)
    }

    data class RouteResult(
        val points: List<GeoPoint>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val isDedicatedBikePath: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configuración obligatoria de osmdroid (User-Agent para política de OSM)
        val context = applicationContext
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapView = binding.mapView

        // 2. Inicialización del Mapa de OpenStreetMap
        setupMapView()

        // 3. Listener de pulsación prolongada en el mapa para fijar destino
        setupMapTapListener()

        // 4. Cargar Puntos de Interés (BiciPARQUEADEROS / Nodos de Rodadas UrbanGO)
        addBikeParkMarkers()

        // 5. Trazar Ruta Ciclista inicial Plaza Bolívar -> UNAL
        calculateRoute(PLAZA_BOLIVAR, UNAL_BOGOTA)

        // 6. Configurar Botones de Interacción (Listeners)
        setupUIListeners()

        // 7. Solicitar Permisos de Ubicación GPS
        checkLocationPermissions()
    }

    private fun setupMapView() {
        mapView.setTileSource(TileSourceFactory.MAPNIK) // Tiles estándar oficiales de OpenStreetMap
        mapView.setMultiTouchControls(true)             // Zoom gestual multitáctil
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.ALWAYS)

        // Centrar la cámara en Bogotá con zoom 14.0
        val mapController = mapView.controller
        mapController.setZoom(14.0)
        mapController.setCenter(BOGOTA_CENTER)

        binding.tvStatus.text = "Mantén presionado el mapa para fijar un destino ciclista"
    }

    private fun setupMapTapListener() {
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { destination ->
                    setDestination(destination)
                    val origin = getCurrentOrigin()
                    calculateRoute(origin, destination)
                }
                return true
            }
        }
        val overlayEvents = MapEventsOverlay(mapEventsReceiver)
        mapView.overlays.add(0, overlayEvents)
    }

    private fun setDestination(destination: GeoPoint) {
        if (destinationMarker == null) {
            destinationMarker = Marker(mapView).apply {
                title = "🎯 Destino Seleccionado"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(destinationMarker)
        }
        destinationMarker?.position = destination
        destinationMarker?.snippet = String.format(Locale.US, "Lat: %.4f | Lon: %.4f", destination.latitude, destination.longitude)
        destinationMarker?.showInfoWindow()
        mapView.invalidate()
        Toast.makeText(this, "Destino fijado. Buscando ciclorrutas seguras...", Toast.LENGTH_SHORT).show()
    }

    private fun getCurrentOrigin(): GeoPoint {
        val myLoc = locationOverlay?.myLocation
        return if (locationOverlay?.isMyLocationEnabled == true && myLoc != null) {
            myLoc
        } else {
            Toast.makeText(this, "GPS inactivo. Usando Plaza de Bolívar como origen", Toast.LENGTH_SHORT).show()
            PLAZA_BOLIVAR
        }
    }

    private fun calculateRoute(origin: GeoPoint, destination: GeoPoint) {
        binding.tvStatus.text = "Calculando ruta ciclista..."

        coroutineScope.launch {
            try {
                val routeData = withContext(Dispatchers.IO) {
                    fetchBikeRoute(origin, destination)
                }

                if (routeData != null && routeData.points.isNotEmpty()) {
                    drawRouteOnMap(routeData)
                } else {
                    binding.tvStatus.text = "No se encontró ruta vial entre estos puntos"
                    Toast.makeText(this@MainActivity, "No se encontró ruta", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "Error al conectar con el servicio de rutas"
                Toast.makeText(this@MainActivity, "Error de red: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Consulta primero el motor de enrutamiento ciclista seguro (BRouter - Red Ciclista de OSM).
     * Si no encuentra conexión por ciclorruta, hace fallback a la red vial vehicular (OSRM).
     */
    private fun fetchBikeRoute(origin: GeoPoint, destination: GeoPoint): RouteResult? {
        // 1. Intentar BRouter con perfil de seguridad ciclista (prioriza ciclorrutas y vías tranquilas)
        try {
            val brouterUrl = "https://brouter.de/brouter?lonlats=${origin.longitude},${origin.latitude}%7C${destination.longitude},${destination.latitude}&profile=safety&alternativeidx=0&format=geojson"
            val request = Request.Builder()
                .url(brouterUrl)
                .header("User-Agent", packageName)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val json = JSONObject(body)
                        val features = json.optJSONArray("features")
                        if (features != null && features.length() > 0) {
                            val firstFeature = features.getJSONObject(0)
                            val properties = firstFeature.optJSONObject("properties")
                            val geometry = firstFeature.optJSONObject("geometry")
                            val coordinates = geometry?.optJSONArray("coordinates")

                            if (coordinates != null && coordinates.length() > 0) {
                                val trackLength = properties?.optString("track-length")?.toDoubleOrNull() ?: 0.0
                                val totalTime = properties?.optString("total-time")?.toDoubleOrNull() ?: 0.0

                                val pointsList = mutableListOf<GeoPoint>()
                                for (i in 0 until coordinates.length()) {
                                    val coord = coordinates.getJSONArray(i)
                                    val lon = coord.getDouble(0)
                                    val lat = coord.getDouble(1)
                                    pointsList.add(GeoPoint(lat, lon))
                                }

                                return RouteResult(
                                    points = pointsList,
                                    distanceMeters = trackLength,
                                    durationSeconds = totalTime,
                                    isDedicatedBikePath = true // Ruta ciclista segura
                                )
                            }
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
            // Continúa al fallback vehicular si BRouter no responde
        }

        // 2. Fallback: Consulta OSRM Vehicular (Tráfico Mixto / Sin ciclorruta dedicada)
        try {
            val osrmUrl = "https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson"
            val request = Request.Builder()
                .url(osrmUrl)
                .header("User-Agent", packageName)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val json = JSONObject(body)
                    if (json.optString("code") == "Ok") {
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val firstRoute = routes.getJSONObject(0)
                            val geometry = firstRoute.getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")
                            val distance = firstRoute.optDouble("distance", 0.0)
                            val duration = firstRoute.optDouble("duration", 0.0)

                            val pointsList = mutableListOf<GeoPoint>()
                            for (i in 0 until coordinates.length()) {
                                val coord = coordinates.getJSONArray(i)
                                val lon = coord.getDouble(0)
                                val lat = coord.getDouble(1)
                                pointsList.add(GeoPoint(lat, lon))
                            }

                            return RouteResult(
                                points = pointsList,
                                distanceMeters = distance,
                                durationSeconds = duration * 2.5, // Estimado de pedaleo en tráfico
                                isDedicatedBikePath = false // Ruta vehicular mixta
                            )
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
            return null
        }

        return null
    }

    private fun drawRouteOnMap(routeResult: RouteResult) {
        // Remover polilínea previa si existe
        currentRoutePolyline?.let {
            mapView.overlays.remove(it)
        }

        // Diferenciación de Color:
        // Verde brillante (#00E676) = Ciclorruta Segura (LTS 1-2)
        // Naranja alerta (#FF6D00) = Tráfico Mixto Vehicular (Precaución / Sin Ciclorruta)
        val routeColor = if (routeResult.isDedicatedBikePath) {
            Color.parseColor("#00E676")
        } else {
            Color.parseColor("#FF6D00")
        }

        val routeTitle = if (routeResult.isDedicatedBikePath) {
            "Ruta Ciclista Segura (Ciclorruta)"
        } else {
            "Ruta Vehicular (Tráfico Mixto)"
        }

        currentRoutePolyline = Polyline(mapView).apply {
            setPoints(routeResult.points)
            outlinePaint.color = routeColor
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            title = routeTitle
        }

        mapView.overlays.add(currentRoutePolyline)
        mapView.invalidate()

        currentRoutePoints = routeResult.points

        val km = String.format(Locale.US, "%.2f", routeResult.distanceMeters / 1000.0)
        val min = (routeResult.durationSeconds / 60.0).roundToInt()

        if (routeResult.isDedicatedBikePath) {
            binding.tvStatus.text = "🚲 Ciclorruta Segura: $km km (~$min min) | Toca botón ruta para encuadrar"
        } else {
            binding.tvStatus.text = "⚠️ Tráfico Mixto (Sin Ciclorruta): $km km (~$min min)"
        }
    }

    private fun clearCurrentRoute() {
        currentRoutePolyline?.let {
            mapView.overlays.remove(it)
            currentRoutePolyline = null
        }
        destinationMarker?.let {
            mapView.overlays.remove(it)
            destinationMarker = null
        }
        currentRoutePoints = emptyList()
        mapView.invalidate()
        binding.tvStatus.text = "Mantén presionado el mapa para trazar una ruta"
    }

    private fun addBikeParkMarkers() {
        // Marcador 1: Universidad Nacional
        val markerUnal = Marker(mapView).apply {
            position = UNAL_BOGOTA
            title = "BiciPARQUEADERO - Universidad Nacional"
            snippet = "Capacidad: 120 cupos | LTS 1 (Ciclorruta Segura)"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(markerUnal)

        // Marcador 2: Plaza de Bolívar
        val markerBolivar = Marker(mapView).apply {
            position = PLAZA_BOLIVAR
            title = "Punto de Encuentro - Rodada Centro Histórico"
            snippet = "Ciclovía Dominical Activa | LTS 1"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(markerBolivar)

        // Marcador 3: Parque de la 93
        val marker93 = Marker(mapView).apply {
            position = PARQUE_93
            title = "BiciPARQUEADERO - Parque de la 93"
            snippet = "Capacidad: 45 cupos | Cámara de Vigilancia"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker93)

        mapView.invalidate()
    }

    private fun setupUIListeners() {
        // Botón GPS: Centrar en posición actual
        binding.fabLocation.setOnClickListener {
            locationOverlay?.let { overlay ->
                if (overlay.isMyLocationEnabled && overlay.myLocation != null) {
                    mapView.controller.animateTo(overlay.myLocation)
                    mapView.controller.setZoom(16.0)
                    Toast.makeText(this, "Centrando en posición GPS...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Obteniendo señal GPS...", Toast.LENGTH_SHORT).show()
                }
            } ?: checkLocationPermissions()
        }

        // Botón Ruta (fabRoute):
        // 1. Clic corto: Encuadra toda la ruta en pantalla (zoomToBoundingBox) para verla completa de un vistazo.
        // 2. Clic largo: Limpia la ruta activa y remueve el marcador de destino.
        binding.fabRoute.setOnClickListener {
            if (currentRoutePoints.isNotEmpty()) {
                val boundingBox = BoundingBox.fromGeoPoints(currentRoutePoints)
                mapView.zoomToBoundingBox(boundingBox, true, 140)
                Toast.makeText(this, "Encuadrando ruta completa en pantalla", Toast.LENGTH_SHORT).show()
            } else {
                val origin = getCurrentOrigin()
                calculateRoute(origin, UNAL_BOGOTA)
                Toast.makeText(this, "Trazando ruta recomendada a la UNAL", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabRoute.setOnLongClickListener {
            clearCurrentRoute()
            Toast.makeText(this, "Ruta y destino borrados", Toast.LENGTH_SHORT).show()
            true
        }

        // Botón Estilo: Alternar Modo Claro vs. Modo Oscuro Nativo de OSM (Sin API Keys)
        binding.fabTileStyle.setOnClickListener {
            if (!isDarkModeMap) {
                mapView.overlayManager.tilesOverlay.setColorFilter(DARK_MODE_FILTER)
                binding.tvStatus.text = "🌙 Modo Oscuro Activado (Filtro Nocturno OSM)"
                isDarkModeMap = true
            } else {
                mapView.overlayManager.tilesOverlay.setColorFilter(null)
                binding.tvStatus.text = "☀️ Modo Estándar Activado: OpenStreetMap"
                isDarkModeMap = false
            }
            mapView.invalidate()
        }
    }

    private fun checkLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_LOCATION)
        } else {
            enableMyLocationOverlay()
        }
    }

    private fun enableMyLocationOverlay() {
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        mapView.overlays.add(locationOverlay)
        mapView.invalidate()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_LOCATION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableMyLocationOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}
