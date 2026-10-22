package com.fiidoopen.explorer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.fiidoopen.explorer.databinding.FragmentCarteBinding
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarteFragment : Fragment() {

    private var _b: FragmentCarteBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        @Suppress("DEPRECATION")
        Configuration.getInstance().load(
            requireContext(),
            android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        )
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _b = FragmentCarteBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        b.map.setTileSource(MapTiles.cyclosm())
        b.map.setMultiTouchControls(true)
        b.map.controller.setZoom(16.5)

        val parked = BikeHub.parked(requireContext())
        if (parked != null) {
            val p = GeoPoint(parked.lat, parked.lon)
            b.map.controller.setCenter(p)
            val marker = Marker(b.map)
            marker.position = p
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Vélo garé"
            b.map.overlays.add(marker)

            val when_ = if (parked.time > 0)
                SimpleDateFormat("dd/MM 'à' HH:mm", Locale.FRANCE).format(Date(parked.time)) else ""
            b.parkedTitle.text = "%.5f, %.5f".format(parked.lat, parked.lon)
            b.parkedSub.text = if (when_.isNotEmpty()) "Garé le $when_" else "Position enregistrée"
        } else {
            b.map.controller.setCenter(GeoPoint(48.8566, 2.3522)) // Paris par défaut
            b.map.controller.setZoom(12.0)
        }

        b.routeBtn.setOnClickListener {
            val pk = BikeHub.parked(requireContext())
            if (pk == null) {
                Toast.makeText(requireContext(), "Aucune position enregistrée pour l'instant.", Toast.LENGTH_SHORT).show()
            } else {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, NavigationFragment.newInstance(pk.lat, pk.lon))
                    .addToBackStack(null)
                    .commit()
            }
        }

        b.guardBtn.setOnClickListener {
            Toast.makeText(requireContext(),
                "Mode gardien : activable une fois le registre identifié (test en cours).",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() { super.onResume(); _b?.map?.onResume() }
    override fun onPause() { super.onPause(); _b?.map?.onPause() }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
