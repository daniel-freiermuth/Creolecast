package com.creolecast.app

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val MAX_PIN_LENGTH = 6

/**
 * Asks for the AirPlay pairing PIN shown on [host]. [onSubmit] gets a non-empty PIN; an empty
 * one is ignored. [onCancel] runs when the user cancels.
 */
internal fun Activity.showPairingPinDialog(
    host: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(MAX_PIN_LENGTH))
        hint = "0000"
    }

    val container = FrameLayout(this).apply {
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(64, 32, 64, 32)
        layoutParams = params
        addView(input)
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.airplay_pin_title)
        .setMessage(getString(R.string.airplay_pin_message, host))
        .setView(container)
        .setPositiveButton(R.string.save) { _, _ ->
            val pin = input.text.toString()
            if (pin.isNotEmpty()) {
                onSubmit(pin)
            }
        }
        .setNegativeButton(R.string.cancel) { _, _ ->
            // Reset PIN request if canceled so it can be triggered again
            onCancel()
        }
        .show()
}
