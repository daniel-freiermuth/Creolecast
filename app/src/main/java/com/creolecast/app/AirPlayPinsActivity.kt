package com.creolecast.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AirPlayPinsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var adapter: PinAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_airplay_pins)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.pinRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)

        adapter = PinAdapter(
            onEdit = { host, currentPin -> showEditDialog(host, currentPin) },
            onDelete = { host -> deletePin(host) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadPins()
    }

    private fun loadPins() {
        val sharedPreferences =
            getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        // PINs are only ever written with putString, so every PIN entry holds a String.
        val pins = sharedPreferences.all.mapNotNull { (key, value) ->
            val pin = value as? String
            if (key.startsWith(PIN_KEY_PREFIX) && pin != null) {
                key.removePrefix(PIN_KEY_PREFIX) to pin
            } else {
                null
            }
        }

        adapter.submitList(pins)
        emptyStateText.visibility = if (pins.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(host: String, currentPin: String) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setText(currentPin)
        }

        val container = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(64, 32, 64, 32)
            layoutParams = params
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.edit_pin))
            .setMessage(host)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newPin = input.text.toString()
                if (newPin.isNotEmpty()) {
                    val sharedPreferences =
                        getSharedPreferences(
                            AudioCastService.SECURE_PREFS_NAME,
                            Context.MODE_PRIVATE
                        )
                    sharedPreferences.edit { putString("$PIN_KEY_PREFIX$host", newPin) }
                    loadPins()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deletePin(host: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_pin))
            .setMessage(host)
            .setPositiveButton(R.string.delete_pin) { _, _ ->
                val sharedPreferences =
                    getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                sharedPreferences.edit { remove("$PIN_KEY_PREFIX$host") }
                loadPins()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class PinAdapter(
        private val onEdit: (String, String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<PinAdapter.ViewHolder>() {

        private var items = emptyList<Pair<String, String>>()

        fun submitList(newItems: List<Pair<String, String>>) {
            val diff = DiffUtil.calculateDiff(PinDiff(items, newItems))
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(
                parent.context
            ).inflate(R.layout.item_airplay_pin, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (host, pin) = items[position]
            holder.hostText.text = host
            holder.pinText.text = pin
            holder.editButton.setOnClickListener { onEdit(host, pin) }
            holder.deleteButton.setOnClickListener { onDelete(host) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val hostText: TextView = view.findViewById(R.id.hostText)
            val pinText: TextView = view.findViewById(R.id.pinText)
            val editButton: ImageButton = view.findViewById(R.id.editButton)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        }
    }

    /** Rows are keyed by host; a changed PIN for the same host is a content change. */
    private class PinDiff(
        private val oldItems: List<Pair<String, String>>,
        private val newItems: List<Pair<String, String>>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldItems.size

        override fun getNewListSize() = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldItems[oldItemPosition].first == newItems[newItemPosition].first

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldItems[oldItemPosition] == newItems[newItemPosition]
    }

    private companion object {
        const val PIN_KEY_PREFIX = "airplay2_pin_"
    }
}
