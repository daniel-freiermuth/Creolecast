package com.creolecast.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PacketLogActivity : AppCompatActivity() {

    private lateinit var logAdapter: PacketLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_packet_log)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.logRecyclerView)
        logAdapter = PacketLogAdapter(PacketLogger.logs.toMutableList())
        recyclerView.adapter = logAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        val searchView = findViewById<SearchView>(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                logAdapter.filter(newText.orEmpty())
                return true
            }
        })

        findViewById<FloatingActionButton>(R.id.clearFab).setOnClickListener {
            PacketLogger.clear()
            logAdapter.clear()
        }

        lifecycleScope.launch {
            PacketLogger.logFlow.collectLatest { log ->
                logAdapter.addLog(log)
            }
        }
    }
}

class PacketLogAdapter(private val allLogs: MutableList<PacketLog>) :
    RecyclerView.Adapter<PacketLogAdapter.ViewHolder>() {

    private val filteredLogs: MutableList<PacketLog> = allLogs.toMutableList()
    private var currentQuery: String = ""

    fun addLog(log: PacketLog) {
        // Check if we should merge with the last log (which is at index 0 because we add new
        // logs at the beginning)
        val lastLog = allLogs.firstOrNull()
        if (lastLog != null && isRepeatOf(lastLog, log)) {
            // It's the same, it was already updated in PacketLogger, so we just update the UI
            // The object in allLogs is the same one that was updated in PacketLogger
            val filteredIndex = filteredLogs.indexOf(lastLog)
            if (filteredIndex != -1) {
                notifyItemChanged(filteredIndex)
            }
            return
        }

        allLogs.add(0, log)
        if (allLogs.size > MAX_LOGS) allLogs.removeAt(allLogs.size - 1)

        if (currentQuery.isEmpty() || matchesQuery(log, currentQuery)) {
            filteredLogs.add(0, log)
            if (filteredLogs.size > MAX_LOGS) filteredLogs.removeAt(filteredLogs.size - 1)
            notifyItemInserted(0)
        }
    }

    /** Same merge rule as [PacketLogger.log]: consecutive identical packets share one row. */
    private fun isRepeatOf(previous: PacketLog, log: PacketLog): Boolean =
        previous.direction == log.direction &&
            previous.type == log.type &&
            previous.message == log.message

    fun clear() {
        val size = filteredLogs.size
        allLogs.clear()
        filteredLogs.clear()
        notifyItemRangeRemoved(0, size)
    }

    // A new search query replaces the whole visible list, so there are no finer-grained
    // change events to report.
    @SuppressLint("NotifyDataSetChanged")
    fun filter(query: String) {
        currentQuery = query
        val matching = if (query.isEmpty()) allLogs else allLogs.filter { matchesQuery(it, query) }
        filteredLogs.clear()
        filteredLogs.addAll(matching)
        notifyDataSetChanged()
    }

    private fun matchesQuery(log: PacketLog, query: String): Boolean =
        log.message.contains(query, ignoreCase = true) ||
            log.type.name.contains(query, ignoreCase = true) ||
            log.direction.name.contains(query, ignoreCase = true)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(
            parent.context
        ).inflate(R.layout.item_packet_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = filteredLogs[position]
        holder.direction.text = log.direction.name
        holder.type.text = log.type.name
        holder.time.text = log.timestamp
        holder.message.text = log.message

        if (log.count > 1) {
            holder.count.visibility = View.VISIBLE
            holder.count.text = holder.itemView.context.getString(
                R.string.packet_log_repeat_count,
                log.count
            )
        } else {
            holder.count.visibility = View.GONE
        }

        val color = when (log.direction) {
            PacketDirection.IN -> ContextCompat.getColor(
                holder.itemView.context,
                R.color.accent_blue
            )

            PacketDirection.OUT -> ContextCompat.getColor(
                holder.itemView.context,
                R.color.md_theme_primary
            )
        }
        holder.direction.setTextColor(color)
    }

    override fun getItemCount(): Int = filteredLogs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val direction: TextView = view.findViewById(R.id.directionText)
        val type: TextView = view.findViewById(R.id.typeText)
        val count: TextView = view.findViewById(R.id.countText)
        val time: TextView = view.findViewById(R.id.timeText)
        val message: TextView = view.findViewById(R.id.messageText)
    }

    private companion object {
        /** Same cap as [PacketLogger] keeps for its own history. */
        const val MAX_LOGS = 500
    }
}
