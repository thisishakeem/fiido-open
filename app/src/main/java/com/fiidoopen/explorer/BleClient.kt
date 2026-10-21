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
    private val onStatus: (Status) -> Unit = {},
) {
    data class DeviceEntry(val device: BluetoothDevice, val name: String, val rssi: Int)
    /** Télémétrie décodée depuis la trame d'état (registre 0x26). */
    data class Status(val batteryPct: Int, val assist: Int, val speedRaw: Int)
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
    var knownBike: String? = null           // adresse MAC du vélo mémorisé
    private var pendingNotify: List<BluetoothGattCharacteristic> = emptyList()
    private var notifyIdx = 0

    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val ffe2: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0dffe2")
    private var writeRef: CharRef? = null
    private var polling = false
    private var pollIndex = 0

    // Requêtes de lecture capturées depuis l'appli officielle (lecture seule, sûres).
    private val pollFrames = listOf(
        byteArrayOf(0x46, 0x64, 0x55, 0x26, 0x1C, 0x4D),  // état principal (batterie/vitesse/assist)
        byteArrayOf(0x46, 0x64, 0x55, 0x0C, 0xC8.toByte(), 0xB3.toByte()),
        byteArrayOf(0x46, 0x64, 0x55, 0x0D, 0x60, 0x1A),
        byteArrayOf(0x46, 0x64, 0x55, 0x0D, 0x7B, 0x01),
        byteArrayOf(0x46, 0x64, 0x55, 0x1C, 0x00, 0x6B),
        byteArrayOf(0x46, 0x64, 0x55, 0x04, 0x53, 0x20),
    )

    /** Construit une trame Fiido : 0x46 0x64 + payload + XOR de tout ce qui précède. */
    fun buildFrame(payload: ByteArray): ByteArray {
        val body = byteArrayOf(0x46, 0x64) + payload
        var x = 0
        for (b in body) x = x xor (b.toInt() and 0xFF)
        return body + byteArrayOf(x.toByte())
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull() ?: "(sans nom)"
            found[dev.address] = DeviceEntry(dev, name, result.rssi)
            onDevices(found.values.sortedByDescending { it.rssi })
            // auto-connexion au vélo mémorisé dès qu'il apparaît
            if (scanning && dev.address.equals(knownBike, true)) {
                log("Vélo mémorisé repéré → connexion directe")
                connect(dev)
            }
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

    /** Connexion directe à une adresse MAC connue, sans scan (le plus rapide). */
    @SuppressLint("MissingPermission")
    fun connectKnown(address: String): Boolean {
        val a = adapter ?: return false
        if (!isBluetoothOn()) { onState("Active le Bluetooth"); return false }
        val dev = try { a.getRemoteDevice(address) } catch (e: Exception) { return false }
        connect(dev)
        return true
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
        stopPolling()
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
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        val ref = CharRef(svc.uuid, c.uuid)
                        writable.add(ref)
                        if (c.uuid == ffe2) writeRef = ref
                    }
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                        notifyList.add(c)
                }
            }
            onWritable(writable.toList())
            pendingNotify = notifyList
            notifyIdx = 0
            main.post { enableNext(g) }
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

        // L'abonnement avance seulement quand l'écriture du descripteur est confirmée.
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("abonné: ${d.characteristic.uuid} (status=$status)")
            main.post { enableNext(g) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNext(g: BluetoothGatt) {
        if (notifyIdx >= pendingNotify.size) {
            onState("Prêt — interrogation du vélo…")
            log("--- abonnements OK ; démarrage de l'interrogation (ffe2) ---")
            startPolling()
            return
        }
        val c = pendingNotify[notifyIdx]; notifyIdx++
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(cccd)
        if (d == null) { main.post { enableNext(g) }; return }
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v)
        } else {
            @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
        }
        // sécurité : si onDescriptorWrite ne se déclenche pas, on continue après 1,5 s
        val idxAtCall = notifyIdx
        main.postDelayed({ if (notifyIdx == idxAtCall) enableNext(g) }, 1500)
    }

    private fun handleIncoming(uuid: UUID, value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        log("→ $uuid  [${value.size}]  $hex")
        FrameDecoder.tryDecode(value)?.let { log("     ⤷ $it") }
        parseStatus(value)
    }

    private fun parseStatus(b: ByteArray) {
        // Trame d'état : 46 64 AA 26 1C <data...> <xor>
        if (b.size < 16) return
        if ((b[0].toInt() and 0xFF) != 0x46 || (b[1].toInt() and 0xFF) != 0x64) return
        if ((b[2].toInt() and 0xFF) != 0xAA || (b[3].toInt() and 0xFF) != 0x26) return
        // Octets calés par comparaison batterie basse (0x0F=15%) vs pleine (0x5A=90%)
        val batt = (b[13].toInt() and 0xFF).coerceIn(0, 100)  // data[8] = % direct
        val assist = b[15].toInt() and 0xFF                   // data[10]
        val speed = b[12].toInt() and 0xFF                    // data[7]
        onStatus(Status(batt, assist, speed))
    }

    /** Règle le niveau d'assistance (0..5) : 46 64 AA 01 26 <niveau> <xor>. Confirmé par capture. */
    fun setAssist(level: Int): Boolean = setReg(0x26, level)

    /** Écrit un registre de réglage : 46 64 AA 01 <reg> <valeur> <xor>. (Commandes capturées = sûres.) */
    fun setReg(reg: Int, value: Int): Boolean {
        val ref = writeRef ?: return false
        return write(ref, buildFrame(byteArrayOf(0xAA.toByte(), 0x01, (reg and 0xFF).toByte(), (value and 0xFF).toByte())))
    }

    fun startPolling() {
        if (polling) return
        polling = true
        pollTick()
    }
    fun stopPolling() { polling = false }

    /** Scan lecture seule des registres 0x00..0x40 : 46 64 55 <reg> 00 <xor>. Sûr (aucune écriture). */
    fun scanRegisters() {
        val ref = writeRef ?: run { log("Scan registres : connecte d'abord le vélo."); return }
        stopPolling()
        log("--- SCAN REGISTRES 0x00..0x40 (lecture seule) ---")
        var reg = 0
        fun step() {
            if (reg > 0x40) { log("--- scan registres terminé ---"); startPolling(); return }
            writeSilent(ref, buildFrame(byteArrayOf(0x55, reg.toByte(), 0x00)))
            reg++
            main.postDelayed({ step() }, 220)
        }
        step()
    }

    private fun pollTick() {
        if (!polling) return
        val ref = writeRef
        if (ref != null) {
            val frame = pollFrames[pollIndex % pollFrames.size]
            pollIndex++
            writeSilent(ref, frame)
        }
        main.postDelayed({ pollTick() }, 350)
    }

    @SuppressLint("MissingPermission")
    private fun writeSilent(ref: CharRef, bytes: ByteArray) {
        val g = gatt ?: return
        val c = g.getService(ref.service)?.getCharacteristic(ref.characteristic) ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type)
        } else {
            @Suppress("DEPRECATION") run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
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
