package com.fiidoopen.explorer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.fiidoopen.explorer.databinding.FragmentNavigationBinding
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationFragment : Fragment() {

    private var _b: FragmentNavigationBinding? = null
    private val b get() = _b!!

    private var destLat = 0.0
    private var destLon = 0.0

    companion object {
        fun newInstance(lat: Double, lon: Double) = NavigationFragment().apply {
            arguments = Bundle().apply { putDouble("lat", lat); putDouble("lon", lon) }
        }
    }

    private fun darkTiles() = object : OnlineTileSourceBase(
        "CartoDark", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap, © CARTO"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        @Suppress("DEPRECATION")
        Configuration.getInstance().load(
            requireContext(),
            android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        )
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _b = FragmentNavigationBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        destLat = arguments?.getDouble("lat") ?: 0.0
        destLon = arguments?.getDouble("lon") ?: 0.0

        b.map.setTileSource(darkTiles())
        b.map.setMultiTouchControls(true)
        b.map.controller.setZoom(15.0)
        val dest = GeoPoint(destLat, destLon)
        b.map.controller.setCenter(dest)

        Marker(b.map).apply {
            position = dest
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Vélo garé"
            b.map.overlays.add(this)
        }

        b.finishBtn.setOnClickListener { parentFragmentManager.popBackStack() }

        val origin = lastKnown()
        if (origin == null) {
            b.navTitle.text = "Position actuelle indisponible"
            b.navDist.text = "—"
            return
        }
        Marker(b.map).apply {
            position = origin
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Vous"
            b.map.overlays.add(this)
        }
        fetchRoute(origin, dest)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): GeoPoint? {
        return try {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return null
            GeoPoint(loc.latitude, loc.longitude)
        } catch (e: Exception) { null }
    }

    private fun fetchRoute(from: GeoPoint, to: GeoPoint) {
        Thread {
            val pts = ArrayList<GeoPoint>()
            var distKm = haversineKm(from, to) // repli : distance à vol d'oiseau
            try {
                val url = URL(
                    "https://router.project-osrm.org/route/v1/cycling/" +
                        "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                        "?overview=full&geometries=geojson"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.inputStream.bufferedReader().use { r ->
                    val json = JSONObject(r.readText())
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    distKm = route.getDouble("distance") / 1000.0
                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    for (i in 0 until coords.length()) {
                        val c = coords.getJSONArray(i)
                        pts.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { /* on garde la distance à vol d'oiseau */ }

            val fDist = distKm
            view?.post { if (isAdded && _b != null) drawAndStats(pts, fDist) }
        }.start()
    }

    private fun drawAndStats(pts: List<GeoPoint>, distKm: Double) {
        if (pts.size >= 2) {
            val line = Polyline().apply {
                setPoints(pts)
                outlinePaint.color = Color.parseColor("#19C3D4")
                outlinePaint.strokeWidth = 12f
            }
            b.map.overlays.add(line)
            b.map.invalidate()
        }

        val timeMin = (distKm / 18.0 * 60.0).roundToInt().coerceAtLeast(1) // ~18 km/h
        b.navDist.text = if (distKm < 1) "${(distKm * 1000).roundToInt()} m" else "%.1f km".format(distKm)
        b.remainTxt.text = if (distKm < 1)
            "${(distKm * 1000).roundToInt()} m · $timeMin min"
        else "%.1f km · %d min".format(distKm, timeMin)

        val eta = Date(System.currentTimeMillis() + timeMin * 60_000L)
        b.etaTxt.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(eta)

        val st = BikeHub.status.value
        if (st != null) {
            val mode = st.assist.coerceIn(1, 5)
            val used = distKm * BikeHub.WH_PER_KM[mode] / BikeHub.BATTERY_WH * 100.0
            val arrive = (st.batteryPct - used).roundToInt().coerceIn(0, 100)
            b.battArriveTxt.text = "$arrive %"
        } else {
            b.battArriveTxt.text = "—"
        }
    }

    private fun haversineKm(a: GeoPoint, c: GeoPoint): Double {
        val r = 6371.0
        val dLat = Math.toRadians(c.latitude - a.latitude)
        val dLon = Math.toRadians(c.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(c.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    override fun onResume() { super.onResume(); _b?.map?.onResume() }
    override fun onPause() { super.onPause(); _b?.map?.onPause() }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
