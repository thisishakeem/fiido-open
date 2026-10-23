package com.fiidoopen.explorer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fiidoopen.explorer.databinding.FragmentHomeBinding
import kotlin.math.abs
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private val names = listOf("OFF", "ÉCO", "NORMAL", "SPORT", "TURBO", "TURBO+")
    private val strong = listOf("#94A3A3", "#2FA36B", "#0C7B88", "#C9781A", "#CC4A3C", "#6A4CE0")
    private val soft = listOf("#F0F3F3", "#E1F5EC", "#D7ECEE", "#FBEBD5", "#FBE2DF", "#EAE4FB")

    private val pills = mutableListOf<TextView>()
    private var currentMode = -1
    private var downX = 0f
    private var startTX = 0f
    private var moved = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        buildPills()
        highlight(-1)
        setupPowerSlider()

        BikeHub.connected.observe(viewLifecycleOwner) { conn -> renderConnection(conn) }
        BikeHub.state.observe(viewLifecycleOwner) { st ->
            if (BikeHub.connected.value != true) {
                b.statusBadge.text = (st ?: "HORS LIGNE").uppercase()
            }
        }
        BikeHub.status.observe(viewLifecycleOwner) { it?.let { st -> renderStatus(st) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildPills() {
        b.modesRow.removeAllViews()
        pills.clear()
        for (i in names.indices) {
            val t = TextView(requireContext()).apply {
                text = names[i]
                gravity = Gravity.CENTER
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(11), 0, dp(11))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(3); marginEnd = dp(3) }
                setOnClickListener {
                    if (BikeHub.isReady() && BikeHub.ble.setAssist(i)) {
                        highlight(i)
                    }
                }
            }
            pills.add(t)
            b.modesRow.addView(t)
        }
    }

    private fun pillBg(radius: Int, color: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }

    private fun highlight(active: Int) {
        currentMode = active
        pills.forEachIndexed { i, t ->
            if (i == active) {
                t.background = pillBg(6, Color.parseColor(strong[i]))
                t.setTextColor(Color.WHITE)
            } else {
                t.background = pillBg(6, Color.parseColor(soft[i]))
                t.setTextColor(Color.parseColor(strong[i]))
            }
        }
    }

    private fun renderConnection(conn: Boolean) {
        if (conn) {
            b.statusBadge.text = "CONNECTÉ"
            b.statusBadge.setBackgroundResource(R.drawable.bg_badge_ok)
            b.statusBadge.setTextColor(Color.parseColor("#0A7A52"))
            b.powerLabel.text = "Glissez pour éteindre"
        } else {
            b.statusBadge.setBackgroundResource(R.drawable.bg_badge_soft)
            b.statusBadge.setTextColor(Color.parseColor("#8A9796"))
            b.battPct.text = "—"
            b.rangeTxt.text = "—"
            b.powerLabel.text = "Glissez pour allumer"
            b.batteryRing.setProgressCompat(0, false)
        }
        restKnob(conn)
    }

    private fun maxTravel(): Float =
        (b.powerTrack.width - b.powerKnob.width - dp(8)).toFloat().coerceAtLeast(0f)

    /** Replace le bouton à sa position de repos selon l'état (gauche=éteint, droite=allumé). */
    private fun restKnob(on: Boolean) {
        b.powerTrack.post {
            b.powerKnob.animate().translationX(if (on) maxTravel() else 0f).setDuration(220).start()
        }
    }

    private fun setupPowerSlider() {
        val knob = b.powerKnob
        // Appui simple = bascule (fiable sur tout appareil)
        knob.setOnClickListener { togglePower() }
        knob.setOnTouchListener { v, e ->
            val max = maxTravel()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX; startTX = v.translationX; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (abs(dx) > dp(6)) moved = true
                    if (max > 0) v.translationX = (startTX + dx).coerceIn(0f, max)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!moved) {
                        v.performClick() // simple appui → bascule
                    } else {
                        val on = BikeHub.connected.value == true
                        val crossed = if (on) v.translationX <= max * 0.5f else v.translationX >= max * 0.5f
                        if (crossed) togglePower()
                        val newOn = if (crossed) !on else on
                        v.animate().translationX(if (newOn) max else 0f).setDuration(140).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePower() {
        if (BikeHub.connected.value == true) BikeHub.ble.powerOff() else BikeHub.ble.powerOn()
    }

    private fun renderStatus(st: BleClient.Status) {
        val pct = st.batteryPct.coerceIn(0, 100)
        val mode = st.assist.coerceIn(0, 5)
        b.battPct.text = "$pct %"
        highlight(mode)

        // Anneau batterie (couleur selon niveau)
        val ring = when {
            pct > 50 -> "#12A06B"
            pct > 20 -> "#E0912A"
            else -> "#DD5B4C"
        }
        b.batteryRing.setIndicatorColor(Color.parseColor(ring))
        b.batteryRing.setProgressCompat(pct, true)

        // Badge mode (coloré)
        b.modeBadge.text = names[mode]
        b.modeBadge.background = pillBg(6, Color.parseColor(soft[mode]))
        b.modeBadge.setTextColor(Color.parseColor(strong[mode]))

        // Autonomie estimée
        if (mode in 1..5) {
            val km = BikeHub.estimatedRangeKm(pct, mode).roundToInt()
            b.rangeTxt.text = "$km km"
        } else {
            b.rangeTxt.text = "— km"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
