package com.fiidoopen.explorer

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * État partagé du vélo, accessible par tous les écrans (fragments).
 * MainActivity crée le BleClient et route ses callbacks vers ces LiveData ;
 * les fragments observent sans se soucier de la couche BLE.
 */
object BikeHub {

    lateinit var ble: BleClient
        private set
    private var initialized = false

    private val _connected = MutableLiveData(false)
    val connected: LiveData<Boolean> = _connected

    private val _state = MutableLiveData("Hors ligne")
    val state: LiveData<String> = _state

    private val _status = MutableLiveData<BleClient.Status?>(null)
    val status: LiveData<BleClient.Status?> = _status

    /** Journal technique (réservé aux outils développeur). */
    private val _log = MutableLiveData("")
    val log: LiveData<String> = _log
    private val logBuf = StringBuilder()

    fun appendLog(line: String) {
        logBuf.append(line).append('\n')
        _log.postValue(logBuf.toString())
    }
    fun logText(): String = logBuf.toString()

    fun postConnected(v: Boolean) = _connected.postValue(v)
    fun postState(s: String) = _state.postValue(s)
    fun postStatus(s: BleClient.Status) = _status.postValue(s)

    /** Dernière position du vélo garé (téléphone au moment de la déconnexion). */
    data class Parked(val lat: Double, val lon: Double, val time: Long)

    fun parked(ctx: Context): Parked? {
        val sp = ctx.getSharedPreferences("fiido", Context.MODE_PRIVATE)
        val lat = sp.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = sp.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Parked(lat, lon, sp.getLong("t", 0))
    }

    fun init(client: BleClient) {
        if (!initialized) { ble = client; initialized = true }
    }

    fun isReady() = initialized

    /** Capacité batterie estimée du Fiido C11 (Wh) pour l'estimation d'autonomie. */
    const val BATTERY_WH = 374.0            // 36 V × 10,4 Ah (approx.)
    /** Consommation moyenne estimée par mode (Wh/km), index = niveau 0..5. */
    val WH_PER_KM = doubleArrayOf(0.0, 7.0, 8.5, 10.5, 13.0, 15.5)

    /** Autonomie restante estimée (km) pour une batterie à [pct] % et un [mode] donné. */
    fun estimatedRangeKm(pct: Int, mode: Int): Double {
        val whkm = WH_PER_KM[mode.coerceIn(1, 5)]
        if (whkm <= 0) return 0.0
        return (BATTERY_WH * pct / 100.0) / whkm
    }
}
