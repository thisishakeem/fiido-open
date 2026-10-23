package com.fiidoopen.explorer

import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.fiidoopen.explorer.databinding.FragmentReglagesBinding
import java.io.File

class ReglagesFragment : Fragment() {

    private var _b: FragmentReglagesBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentReglagesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        b.versionTxt.text = "Fiido Open ${BuildStamp.VERSION}"
        b.logView.movementMethod = ScrollingMovementMethod()

        b.btnPowerOn.setOnClickListener { ifReady { BikeHub.ble.setReg(0x27, 0x80); BikeHub.appendLog("Alimentation → ON (0x27=0x80)") } }
        b.btnPowerOff.setOnClickListener { ifReady { BikeHub.ble.setReg(0x27, 0x08); BikeHub.appendLog("Alimentation → OFF (0x27=0x08)") } }

        b.devHeader.setOnClickListener {
            val show = b.devBox.visibility != View.VISIBLE
            b.devBox.visibility = if (show) View.VISIBLE else View.GONE
            b.devHeader.text = if (show) "▾ Outils développeur" else "▸ Outils développeur"
        }

        b.btnReconnect.setOnClickListener { (activity as? MainActivity)?.tryAutoConnect() }
        b.btnScanReg.setOnClickListener { ifReady { BikeHub.ble.scanRegisters() } }
        b.btnT2708.setOnClickListener { ifReady { BikeHub.ble.setReg(0x27, 0x08); BikeHub.appendLog("test → 0x27 = 0x08") } }
        b.btnT2780.setOnClickListener { ifReady { BikeHub.ble.setReg(0x27, 0x80); BikeHub.appendLog("test → 0x27 = 0x80") } }
        b.btnT2700.setOnClickListener { ifReady { BikeHub.ble.setReg(0x27, 0x00); BikeHub.appendLog("test → 0x27 = 0x00") } }
        b.btnT2B40.setOnClickListener { ifReady { BikeHub.ble.setReg(0x2B, 0x40); BikeHub.appendLog("test → 0x2B = 0x40") } }
        b.btnT2B00.setOnClickListener { ifReady { BikeHub.ble.setReg(0x2B, 0x00); BikeHub.appendLog("test → 0x2B = 0x00") } }
        b.btnShareLog.setOnClickListener { shareLog() }

        val notMapped = { _: android.widget.CompoundButton, _: Boolean ->
            Toast.makeText(requireContext(),
                "Réglage disponible une fois le registre identifié (test en cours).",
                Toast.LENGTH_SHORT).show()
        }
        b.swThrottle.setOnCheckedChangeListener(notMapped)
        b.swWalk.setOnCheckedChangeListener(notMapped)

        BikeHub.log.observe(viewLifecycleOwner) { txt ->
            b.logView.text = txt
            val scroll = b.logView.layout?.getLineTop(b.logView.lineCount) ?: 0
            val pad = scroll - b.logView.height + b.logView.paddingBottom
            if (pad > 0) b.logView.scrollTo(0, pad)
        }
    }

    private inline fun ifReady(block: () -> Unit) {
        if (BikeHub.isReady()) block()
        else Toast.makeText(requireContext(), "Connecte d'abord le vélo.", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        try {
            val f = File(requireContext().cacheDir, "fiido_ble_log.txt")
            f.writeText(BikeHub.logText())
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", f
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Partager le journal"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Partage impossible: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
