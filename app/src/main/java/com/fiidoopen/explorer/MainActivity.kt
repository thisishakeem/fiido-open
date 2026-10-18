package com.fiidoopen.explorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
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
            if (res.values.all { it }) startScan()
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
            onWritable = { list ->
                runOnUiThread {
                    writable = list
                    val labels = list.map { it.toString() }
                    b.charSpinner.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_dropdown_item, labels
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
        appendLog("1) Scan  2) laisse-le se connecter  3) partage-moi le journal.")
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
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        if (!ble.isBluetoothOn()) { appendLog("Active le Bluetooth puis relance le scan."); return }
        ble.startScan()
    }

    private fun sendHex() {
        val raw = b.hexInput.text.toString().trim()
        val bytes = parseHex(raw)
        if (bytes == null) { appendLog("Hex invalide. Ex: AA 03 01 05"); return }
        val pos = b.charSpinner.selectedItemPosition
        if (pos !in writable.indices) { appendLog("Aucune caractéristique d'écriture — connecte d'abord."); return }
        val ok = ble.write(writable[pos], bytes)
        if (!ok) appendLog("Échec de l'écriture.")
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(",", " ").replace("0x", " ", ignoreCase = true)
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        return try {
            ByteArray(clean.size) { clean[it].toInt(16).toByte() }
        } catch (e: Exception) { null }
    }

    private fun appendLog(line: String) {
        logBuf.append(ts).append("  ").append(line).append('\n')
        b.logView.text = logBuf
        // auto-scroll
        val scroll = b.logView.layout?.getLineTop(b.logView.lineCount) ?: 0
        val pad = scroll - b.logView.height + b.logView.paddingBottom
        if (pad > 0) b.logView.scrollTo(0, pad)
    }

    private fun shareLog() {
        try {
            val f = File(cacheDir, "fiido_ble_log.txt")
            f.writeText(logBuf.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f
            )
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Partager le journal"))
        } catch (e: Exception) {
            appendLog("Partage impossible: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.disconnect()
    }
}
