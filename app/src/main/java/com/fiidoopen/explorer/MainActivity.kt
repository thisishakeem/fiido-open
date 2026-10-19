package com.fiidoopen.explorer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fiidoopen.explorer.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var ble: BleClient
    private val logBuf = StringBuilder()
    private val ts get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    private var writable: List<BleClient.CharRef> = emptyList()
    private var wasConnected = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            // On a besoin du Bluetooth pour scanner ; la localisation est optionnelle (vélo garé).
            val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                res[Manifest.permission.BLUETOOTH_SCAN] == true && res[Manifest.permission.BLUETOOTH_CONNECT] == true
            else true
            if (btOk) ble.startScan() else appendLog("Permissions Bluetooth refusées — impossible de scanner.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.logView.movementMethod = ScrollingMovementMethod()

        ble = BleClient(
            context = this,
            log = { line -> runOnUiThread { appendLog(line) } },
            onState = { s -> runOnUiThread { b.status.text = s } },
            onDevices = { list -> runOnUiThread { renderDevices(list) } },
            onWritable = { list ->
                runOnUiThread {
                    writable = list
                    b.charSpinner.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, list.map { it.toString() }
                    )
                }
            },
            onStatus = { st -> runOnUiThread { showStatus(st) } }
        )

        b.scanBtn.setOnClickListener { ensurePermsThenScan() }
        b.disconnectBtn.setOnClickListener { captureParked(); ble.disconnect() }
        b.sendBtn.setOnClickListener { sendHex() }
        b.shareBtn.setOnClickListener { shareLog() }
        b.mapBtn.setOnClickListener { openMap() }
        b.toggleLog.setOnClickListener {
            b.logBox.visibility = if (b.logBox.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        showParkedFromPrefs()
        appendLog("Fiido Open ${BuildStamp.VERSION}")
    }

    private fun showStatus(st: BleClient.Status) {
        wasConnected = true
        b.battPct.text = "${st.batteryPct} %"
        b.battVolt.text = "%.2f V  ·  estimation".format(st.voltage)
        b.speed.text = "${st.speedRaw}"
        b.assist.text = "${st.assist}"
    }

    private fun renderDevices(list: List<BleClient.DeviceEntry>) {
        b.deviceList.removeAllViews()
        for (d in list) {
            val btn = Button(this)
            val bars = when {
                d.rssi >= -55 -> "proche"
                d.rssi >= -70 -> "moyen"
                d.rssi >= -85 -> "loin"
                else -> "très loin"
            }
            btn.text = "${d.name}\n${d.device.address}   ${d.rssi} dBm   $bars"
            btn.isAllCaps = false
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (d.rssi >= -60) btn.setTextColor(Color.parseColor("#8FF0B4"))
            btn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            btn.setOnClickListener { b.deviceList.removeAllViews(); ble.connect(d.device) }
            b.deviceList.addView(btn)
        }
    }

    private fun neededPerms(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            l.add(Manifest.permission.BLUETOOTH_SCAN); l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        l.add(Manifest.permission.ACCESS_FINE_LOCATION) // pour géoloc vélo garé
        return l.toTypedArray()
    }

    private fun ensurePermsThenScan() {
        val missing = neededPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (!ble.isBluetoothOn()) appendLog("Active le Bluetooth puis relance le scan.") else ble.startScan()
        } else permLauncher.launch(missing.toTypedArray())
    }

    // ---- Vélo garé (GPS côté téléphone) ----
    private fun captureParked() {
        if (!wasConnected) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val sp = getSharedPreferences("fiido", Context.MODE_PRIVATE)
                sp.edit().putString("lat", loc.latitude.toString())
                    .putString("lon", loc.longitude.toString())
                    .putLong("t", System.currentTimeMillis()).apply()
                showParkedFromPrefs()
                appendLog("Position du vélo enregistrée.")
            }
        } catch (e: Exception) { appendLog("GPS indisponible: ${e.message}") }
    }

    private fun showParkedFromPrefs() {
        val sp = getSharedPreferences("fiido", Context.MODE_PRIVATE)
        val lat = sp.getString("lat", null); val lon = sp.getString("lon", null)
        if (lat != null && lon != null) {
            val t = sp.getLong("t", 0)
            val when_ = if (t > 0) SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(t)) else ""
            b.parked.text = "$lat, $lon\nEnregistré le $when_"
        }
    }

    private fun openMap() {
        val sp = getSharedPreferences("fiido", Context.MODE_PRIVATE)
        val lat = sp.getString("lat", null); val lon = sp.getString("lon", null)
        if (lat == null || lon == null) { appendLog("Aucune position enregistrée."); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Vélo)")))
        } catch (e: Exception) { appendLog("Pas d'appli carte: ${e.message}") }
    }

    private fun sendHex() {
        val bytes = parseHex(b.hexInput.text.toString().trim())
        if (bytes == null) { appendLog("Hex invalide."); return }
        val pos = b.charSpinner.selectedItemPosition
        if (pos !in writable.indices) { appendLog("Connecte d'abord."); return }
        if (!ble.write(writable[pos], bytes)) appendLog("Échec écriture.")
    }

    private fun parseHex(s: String): ByteArray? {
        val c = s.replace(",", " ").replace("0x", " ", true).split(Regex("\\s+")).filter { it.isNotBlank() }
        return try { ByteArray(c.size) { c[it].toInt(16).toByte() } } catch (e: Exception) { null }
    }

    private fun appendLog(line: String) {
        logBuf.append(ts).append("  ").append(line).append('\n')
        b.logView.text = logBuf
        val scroll = b.logView.layout?.getLineTop(b.logView.lineCount) ?: 0
        val pad = scroll - b.logView.height + b.logView.paddingBottom
        if (pad > 0) b.logView.scrollTo(0, pad)
    }

    private fun shareLog() {
        try {
            val f = File(cacheDir, "fiido_ble_log.txt"); f.writeText(logBuf.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Partager le journal"))
        } catch (e: Exception) { appendLog("Partage impossible: ${e.message}") }
    }

    override fun onDestroy() { super.onDestroy(); ble.disconnect() }
}
