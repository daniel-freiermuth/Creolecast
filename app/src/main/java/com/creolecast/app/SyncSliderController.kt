package com.creolecast.app

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider

/**
 * MainActivity's per-destination delay sliders, shown only while casting to more than one
 * destination. Each slider row is tagged with its destination host.
 */
internal class SyncSliderController(
    private val layoutInflater: LayoutInflater,
    private val syncSection: View,
    private val syncSliderContainer: LinearLayout
) {
    private val activeTouchHosts = mutableSetOf<String>()

    fun update(service: AudioCastService?) {
        val destinations = service
            ?.takeIf { it.state.value == CastState.CASTING }
            ?.activeDestinations
            ?.value
            .orEmpty()
        if (service == null || destinations.size <= 1) {
            syncSection.visibility = View.GONE
            return
        }
        syncSection.visibility = View.VISIBLE

        val existingSliders = keepCurrentSliders(destinations.map { it.host })
        destinations.forEach { dest ->
            val sliderItem = existingSliders[dest.host] ?: addSlider(dest.host)
            bindSlider(sliderItem, dest, service)
        }
    }

    /** Removes slider rows for hosts no longer cast to; returns the remaining rows by host. */
    private fun keepCurrentSliders(currentHosts: List<String>): Map<String, View> {
        val existingSliders = mutableMapOf<String, View>()
        for (i in 0 until syncSliderContainer.childCount) {
            val child = syncSliderContainer.getChildAt(i)
            val host = child.tag as? String
            if (host != null) {
                if (host in currentHosts) {
                    existingSliders[host] = child
                } else {
                    syncSliderContainer.removeView(child)
                }
            }
        }
        return existingSliders
    }

    private fun addSlider(host: String): View {
        val sliderItem =
            layoutInflater.inflate(R.layout.item_sync_slider, syncSliderContainer, false)
        sliderItem.tag = host
        syncSliderContainer.addView(sliderItem)
        return sliderItem
    }

    private fun bindSlider(sliderItem: View, dest: CastDestination, service: AudioCastService) {
        val label = sliderItem.findViewById<TextView>(R.id.label)
        val slider = sliderItem.findViewById<Slider>(R.id.slider)

        label.text = delayLabel(label, dest.name, dest.delayMs)

        if (!activeTouchHosts.contains(dest.host)) {
            configureSlider(slider, label, dest, service)
        }
    }

    private fun configureSlider(
        slider: Slider,
        label: TextView,
        dest: CastDestination,
        service: AudioCastService
    ) {
        slider.valueFrom = 0f
        slider.valueTo = MAX_DELAY_MS
        slider.stepSize = DELAY_STEP_MS
        slider.value = dest.delayMs.toFloat().coerceIn(0f, MAX_DELAY_MS)

        slider.clearOnChangeListeners()
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                service.setDelay(dest.host, value.toInt())
                label.text = delayLabel(label, dest.name, value.toInt())
                slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                activeTouchHosts.add(dest.host)
                slider.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                activeTouchHosts.remove(dest.host)
                service.setDelay(dest.host, slider.value.toInt())
                slider.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        })
    }

    private fun delayLabel(label: TextView, name: String, delayMs: Int): String =
        label.context.getString(R.string.sync_delay_label, name, delayMs)

    private companion object {
        const val MAX_DELAY_MS = 2000f
        const val DELAY_STEP_MS = 20f
    }
}
