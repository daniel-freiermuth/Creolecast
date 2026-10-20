package com.creolecast.app

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.creolecast.app.cast.SecretKind
import com.creolecast.app.cast.SecretRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val MAX_PIN_LENGTH = 6

/**
 * Asks for what [request] wants: the AirPlay pairing PIN shown on the receiver, or its access
 * password. [onSubmit] gets a non-empty answer; an empty one is ignored. [onCancel] runs when
 * the user cancels.
 */
internal fun Activity.showPairingPinDialog(
    request: SecretRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val isPin = request.kind == SecretKind.PIN
    val input = EditText(this).apply {
        if (isPin) {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(MAX_PIN_LENGTH))
            hint = "0000"
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
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
        .setTitle(if (isPin) R.string.airplay_pin_title else R.string.airplay_password_title)
        .setMessage(
            getString(
                if (isPin) R.string.airplay_pin_message else R.string.airplay_password_message,
                request.host
            )
        )
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
