package com.fiidoopen.explorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) ble.startScan()
            else appendLog("Permissions refusées — impossible de scanner.")
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
            }
        )

        b.scanBtn.setOnClickListener { ensurePermsThenScan() }
        b.disconnectBtn.setOnClickListener { ble.disconnect() }
        b.sendBtn.setOnClickListener { sendHex() }
        b.shareBtn.setOnClickListener { shareLog() }
        b.clearBtn.setOnClickListener { logBuf.setLength(0); b.logView.text = "" }

        appendLog("Fiido BLE Explorer ${BuildStamp.VERSION}")
        appendLog("Scan → choisis l'appareil au signal le plus fort (ton vélo) → partage le journal.")
    }

    private fun renderDevices(list: List<BleClient.DeviceEntry>) {
        b.deviceList.removeAllViews()
        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Aucun appareil pour l'instant…"
            tv.setPadding(8, 8, 8, 8)
            b.deviceList.addView(tv)
            return
        }
        for (d in list) {
            val btn = Button(this)
            val bars = when {
                d.rssi >= -55 -> "●●●● proche"
                d.rssi >= -70 -> "●●●○"
                d.rssi >= -85 -> "●●○○"
                else -> "●○○○ loin"
            }
            btn.text = "${d.name}\n${d.device.address}   ${d.rssi} dBm   $bars"
            btn.isAllCaps = false
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (d.rssi >= -60) btn.setTextColor(Color.parseColor("#1b5e20"))
            btn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            btn.setOnClickListener { ble.connect(d.device) }
            b.deviceList.addView(btn)
        }
    }

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsThenScan() {
        val missing = neededPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (!ble.isBluetoothOn()) appendLog("Active le Bluetooth puis relance le scan.")
            else ble.startScan()
        } else permLauncher.launch(missing.toTypedArray())
    }

    private fun sendHex() {
        val bytes = parseHex(b.hexInput.text.toString().trim())
        if (bytes == null) { appendLog("Hex invalide. Ex: AA 03 01 05"); return }
        val pos = b.charSpinner.selectedItemPosition
        if (pos !in writable.indices) { appendLog("Aucune caractéristique d'écriture — connecte d'abord."); return }
        if (!ble.write(writable[pos], bytes)) appendLog("Échec de l'écriture.")
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(",", " ").replace("0x", " ", ignoreCase = true)
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        return try { ByteArray(clean.size) { clean[it].toInt(16).toByte() } } catch (e: Exception) { null }
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
            val f = File(cacheDir, "fiido_ble_log.txt")
            f.writeText(logBuf.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Partager le journal"))
        } catch (e: Exception) { appendLog("Partage impossible: ${e.message}") }
    }

    override fun onDestroy() { super.onDestroy(); ble.disconnect() }
}
