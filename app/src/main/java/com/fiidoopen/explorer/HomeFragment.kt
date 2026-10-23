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
                t.background = pillBg(13, Color.parseColor(strong[i]))
                t.setTextColor(Color.WHITE)
            } else {
                t.background = pillBg(13, Color.parseColor(soft[i]))
                t.setTextColor(Color.parseColor(strong[i]))
            }
        }
    }

    private fun renderConnection(conn: Boolean) {
        if (conn) {
            b.statusBadge.text = "CONNECTÉ"
            b.statusBadge.setBackgroundResource(R.drawable.bg_badge_ok)
            b.statusBadge.setTextColor(Color.parseColor("#0A7A52"))
            b.powerTrack.setBackgroundResource(R.drawable.bg_slider_on)
            b.powerLabel.text = "Glisser pour éteindre"
        } else {
            b.statusBadge.setBackgroundResource(R.drawable.bg_badge_soft)
            b.statusBadge.setTextColor(Color.parseColor("#8A9796"))
            b.battPct.text = "—"
            b.rangeTxt.text = "—"
            b.powerTrack.setBackgroundResource(R.drawable.bg_slider)
            b.powerLabel.text = "Glisser pour allumer"
            b.batteryRing.setProgressCompat(0, false)
        }
    }

    private fun setupPowerSlider() {
        val knob = b.powerKnob
        knob.setOnTouchListener { v, e ->
            val max = (b.powerTrack.width - v.width - dp(8)).toFloat().coerceAtLeast(0f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX; startTX = v.translationX; true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.translationX = (startTX + (e.rawX - downX)).coerceIn(0f, max); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (max > 0 && v.translationX >= max * 0.8f) {
                        v.animate().translationX(max).setDuration(120).withEndAction {
                            triggerPower()
                            v.postDelayed({ v.animate().translationX(0f).setDuration(220).start() }, 500)
                        }.start()
                    } else {
                        v.animate().translationX(0f).setDuration(160).start()
                    }
                    v.performClick(); true
                }
                else -> false
            }
        }
    }

    private fun triggerPower() {
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
        b.modeBadge.background = pillBg(999, Color.parseColor(soft[mode]))
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
