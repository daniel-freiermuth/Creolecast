package com.creolecast.app

import android.content.Context
import android.os.Bundle
import android.text.InputType
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
import com.creolecast.app.airplay2.AIRPLAY2_CREDENTIAL_PREFIX
import com.creolecast.app.cast.AIRPLAY_PASSWORD_PREFIX
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

        adapter = PinAdapter(onEdit = ::showEditDialog, onDelete = ::deleteEntry)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadEntries()
    }

    /** One stored secret: a receiver's password (editable) or an AirPlay 2 pairing. */
    private data class Entry(val key: String, val title: String, val subtitle: String) {
        val editable: Boolean get() = key.startsWith(AIRPLAY_PASSWORD_PREFIX)
    }

    private fun securePrefs() =
        getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadEntries() {
        val entries = securePrefs().all.keys.mapNotNull { key ->
            when {
                key.startsWith(AIRPLAY_PASSWORD_PREFIX) ->
                    Entry(key, key.removePrefix(AIRPLAY_PASSWORD_PREFIX), PASSWORD_MASK)

                key.startsWith(AIRPLAY2_CREDENTIAL_PREFIX) -> Entry(
                    key,
                    key.removePrefix(AIRPLAY2_CREDENTIAL_PREFIX),
                    getString(R.string.airplay2_paired)
                )

                else -> null
            }
        }.sortedBy { it.title }

        adapter.submitList(entries)
        emptyStateText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(entry: Entry) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
            .setMessage(entry.title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val password = input.text.toString()
                if (password.isNotEmpty()) {
                    securePrefs().edit { putString(entry.key, password) }
                    loadEntries()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteEntry(entry: Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_pin))
            .setMessage(entry.title)
            .setPositiveButton(R.string.delete_pin) { _, _ ->
                securePrefs().edit { remove(entry.key) }
                loadEntries()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class PinAdapter(
        private val onEdit: (Entry) -> Unit,
        private val onDelete: (Entry) -> Unit
    ) : RecyclerView.Adapter<PinAdapter.ViewHolder>() {

        private var items = emptyList<Entry>()

        fun submitList(newItems: List<Entry>) {
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
            val entry = items[position]
            holder.hostText.text = entry.title
            holder.pinText.text = entry.subtitle
            holder.editButton.visibility = if (entry.editable) View.VISIBLE else View.GONE
            holder.editButton.setOnClickListener { onEdit(entry) }
            holder.deleteButton.setOnClickListener { onDelete(entry) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val hostText: TextView = view.findViewById(R.id.hostText)
            val pinText: TextView = view.findViewById(R.id.pinText)
            val editButton: ImageButton = view.findViewById(R.id.editButton)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        }
    }

    /** Rows are keyed by their preference key. */
    private class PinDiff(private val oldItems: List<Entry>, private val newItems: List<Entry>) :
        DiffUtil.Callback() {
        override fun getOldListSize() = oldItems.size

        override fun getNewListSize() = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldItems[oldItemPosition].key == newItems[newItemPosition].key

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldItems[oldItemPosition] == newItems[newItemPosition]
    }

    private companion object {
        const val PASSWORD_MASK = "••••••"
    }
}
