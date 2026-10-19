package com.fiidoopen.explorer

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Explorateur BLE pour le vélo Fiido.
 * v0.2 : liste TOUS les appareils détectés (nom + MAC + RSSI) et laisse
 * l'utilisateur choisir lequel connecter — plus de connexion automatique.
 * Une fois connecté : abonnement à toutes les caractéristiques "notify",
 * journal des trames en hexa, et écriture de trames de test.
 */
class BleClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onDevices: (List<DeviceEntry>) -> Unit,
    private val onWritable: (List<CharRef>) -> Unit,
) {
    data class DeviceEntry(val device: BluetoothDevice, val name: String, val rssi: Int)
    data class CharRef(val service: UUID, val characteristic: UUID) {
        override fun toString() = "$service / $characteristic"
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private val main = Handler(Looper.getMainLooper())
    private val writable = mutableListOf<CharRef>()
    private val found = LinkedHashMap<String, DeviceEntry>()
    private var scanning = false

    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull() ?: "(sans nom)"
            found[dev.address] = DeviceEntry(dev, name, result.rssi)
            onDevices(found.values.sortedByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) { onState("Échec du scan ($errorCode)") }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null) { onState("Bluetooth indisponible"); return }
        if (!isBluetoothOn()) { onState("Active le Bluetooth"); return }
        found.clear(); onDevices(emptyList())
        scanning = true
        onState("Scan… approche-toi du vélo")
        log("--- scan démarré (choisis l'appareil au signal le plus fort) ---")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        main.postDelayed({ if (scanning) { stopScan(); onState("Scan terminé — appuie à nouveau sur Scan si besoin") } }, 25000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        onState("Connexion à ${device.address}…")
        log("--- connexion à ${device.address} ---")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        onState("Déconnecté")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState("Connecté — découverte des services…")
                main.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onState("Déconnecté (status=$status)")
                log("--- déconnecté (status=$status) ---")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("=== Services (status=$status) ===")
            writable.clear()
            val notifyList = mutableListOf<BluetoothGattCharacteristic>()
            for (svc in g.services) {
                log("Service ${svc.uuid}")
                for (c in svc.characteristics) {
                    val p = c.properties
                    val flags = buildString {
                        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                    }
                    log("   • ${c.uuid}  [$flags]")
                    if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                        writable.add(CharRef(svc.uuid, c.uuid))
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                        notifyList.add(c)
                }
            }
            onWritable(writable.toList())
            main.post { enableNext(g, notifyList, 0) }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleIncoming(c.uuid, c.value ?: ByteArray(0))
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log("← write ${c.uuid} status=$status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNext(g: BluetoothGatt, list: List<BluetoothGattCharacteristic>, i: Int) {
        if (i >= list.size) {
            onState("Prêt — ${list.size} notif. actives")
            log("--- abonnements terminés, écoute en cours ---")
            return
        }
        val c = list[i]
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(cccd)
        if (d != null) {
            val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, v)
            } else {
                @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
            }
            log("abonné: ${c.uuid}")
        }
        main.postDelayed({ enableNext(g, list, i + 1) }, 250)
    }

    private fun handleIncoming(uuid: UUID, value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        log("→ $uuid  [${value.size}]  $hex")
        FrameDecoder.tryDecode(value)?.let { log("     ⤷ $it") }
    }

    @SuppressLint("MissingPermission")
    fun write(ref: CharRef, bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val svc = g.getService(ref.service) ?: return false
        val c = svc.getCharacteristic(ref.characteristic) ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        log("TX ${ref.characteristic}  $hex")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
    }
}
