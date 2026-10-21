package com.fiidoopen.explorer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.fiidoopen.explorer.databinding.FragmentTrajetsBinding

class TrajetsFragment : Fragment() {

    private var _b: FragmentTrajetsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentTrajetsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        b.exportBtn.setOnClickListener {
            Toast.makeText(requireContext(),
                "Export Health Connect : disponible dès que l'enregistrement des trajets sera actif.",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
