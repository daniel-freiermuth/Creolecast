package com.creolecast.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Renders a [CastState] into MainActivity's status card: state label, cast button, colours. */
internal class CastStatusCard(
    private val statusCard: MaterialCardView,
    private val stateTextView: TextView,
    private val castButton: MaterialButton
) {
    private var currentCardAnimator: ValueAnimator? = null

    fun render(state: CastState, hasSelectedServer: Boolean) {
        val oldStateText = stateTextView.text.toString()
        if (oldStateText != state.name) {
            stateTextView.animate().alpha(0f).setDuration(LABEL_FADE_MS).withEndAction {
                stateTextView.text = state.name
                stateTextView.animate().alpha(1f).setDuration(LABEL_FADE_MS).start()
            }.start()
        }

        castButton.isEnabled =
            (state == CastState.OFF && hasSelectedServer) || state == CastState.CASTING

        val context = statusCard.context
        val activeColor = ContextCompat.getColor(context, R.color.accent_blue)
        val idleColor = ContextCompat.getColor(context, R.color.light_grey)
        val surfaceColor = ContextCompat.getColor(context, R.color.surface_card)
        val activeTint = ColorUtils.setAlphaComponent(activeColor, ACTIVE_BACKGROUND_ALPHA)

        if (state == CastState.CASTING) {
            castButton.text = context.getString(R.string.stop)
            castButton.setIconResource(android.R.drawable.ic_media_pause)
            animateCardColors(idleColor, activeColor, surfaceColor, activeTint)
        } else {
            castButton.text = context.getString(R.string.start)
            castButton.setIconResource(android.R.drawable.ic_media_play)
            animateCardColors(activeColor, idleColor, activeTint, surfaceColor)
        }
    }

    private fun animateCardColors(fromStroke: Int, toStroke: Int, fromBg: Int, toBg: Int) {
        currentCardAnimator?.cancel()

        currentCardAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CARD_COLOR_ANIMATION_MS
            val argbEvaluator = ArgbEvaluator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val strokeColor = argbEvaluator.evaluate(fraction, fromStroke, toStroke) as Int
                val bgColor = argbEvaluator.evaluate(fraction, fromBg, toBg) as Int

                statusCard.setStrokeColor(ColorStateList.valueOf(strokeColor))
                statusCard.setCardBackgroundColor(ColorStateList.valueOf(bgColor))
            }
            start()
        }
    }

    private companion object {
        const val LABEL_FADE_MS = 150L
        const val CARD_COLOR_ANIMATION_MS = 400L

        /** Alpha (0-255) of the accent colour used as the card background while casting. */
        const val ACTIVE_BACKGROUND_ALPHA = 40
    }
}
