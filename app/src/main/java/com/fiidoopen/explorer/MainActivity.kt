package com.fiidoopen.explorer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fiidoopen.explorer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var ble: BleClient
    private var wasConnected = false
    private var autoConnectAfterPerm = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                res[Manifest.permission.BLUETOOTH_SCAN] == true && res[Manifest.permission.BLUETOOTH_CONNECT] == true
            else true
            if (!btOk) { BikeHub.postState("Permissions Bluetooth refusées"); return@registerForActivityResult }
            if (autoConnectAfterPerm) { autoConnectAfterPerm = false; connectKnownNow() } else ble.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ble = BleClient(
            context = applicationContext,
            log = { line -> BikeHub.appendLog(line) },
            onState = { s -> onBleState(s) },
            onDevices = { },
            onWritable = { },
            onStatus = { st ->
                wasConnected = true
                BikeHub.postConnected(true)
                BikeHub.postStatus(st)
            }
        )
        BikeHub.init(ble)

        ble.knownBike = getSharedPreferences("fiido", Context.MODE_PRIVATE)
            .getString("bikeAddress", "D7:39:0E:7E:FB:0E")

        b.bottomNav.setOnItemSelectedListener { item ->
            val f: Fragment = when (item.itemId) {
                R.id.nav_bike -> HomeFragment()
                R.id.nav_rides -> TrajetsFragment()
                R.id.nav_map -> CarteFragment()
                R.id.nav_settings -> ReglagesFragment()
                else -> HomeFragment()
            }
            supportFragmentManager.beginTransaction().replace(R.id.container, f).commit()
            true
        }
        if (savedInstanceState == null) b.bottomNav.selectedItemId = R.id.nav_bike

        BikeHub.appendLog("Fiido Open ${BuildStamp.VERSION}")
        tryAutoConnect()
    }

    private fun onBleState(s: String) {
        BikeHub.postState(s)
        if (s.startsWith("Déconnecté")) {
            if (wasConnected) captureParked()
            wasConnected = false
            BikeHub.postConnected(false)
        }
    }

    fun tryAutoConnect() {
        val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, connectPerm) == PackageManager.PERMISSION_GRANTED) {
            connectKnownNow()
        } else {
            autoConnectAfterPerm = true
            permLauncher.launch(neededPerms())
        }
    }

    private fun connectKnownNow() {
        val addr = ble.knownBike ?: return
        if (!ble.isBluetoothOn()) { BikeHub.postState("Active le Bluetooth"); return }
        if (!ble.connectKnown(addr)) { BikeHub.postState("Connexion impossible — relance"); ble.startScan() }
    }

    private fun neededPerms(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            l.add(Manifest.permission.BLUETOOTH_SCAN); l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return l.toTypedArray()
    }

    private fun captureParked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                getSharedPreferences("fiido", Context.MODE_PRIVATE).edit()
                    .putString("lat", loc.latitude.toString())
                    .putString("lon", loc.longitude.toString())
                    .putLong("t", System.currentTimeMillis()).apply()
                BikeHub.appendLog("Position du vélo enregistrée.")
            }
        } catch (e: Exception) { BikeHub.appendLog("GPS indisponible: ${e.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.disconnect()
    }
}
