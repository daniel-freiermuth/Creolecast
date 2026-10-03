package com.creolecast.app

import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ServerAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onDeleteClick: (Server) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    private var servers = emptyList<Server>()
    private var selectedItem = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.serverName.text = server.name

        val context = holder.itemView.context
        holder.serverHost.text =
            context.getString(R.string.server_host_platform, server.host, server.platform.label)
        val colorRes = ContextCompat.getColor(context, R.color.accent_blue)

        if (selectedItem == position) {
            holder.cardView.setStrokeWidth(SELECTED_STROKE_WIDTH_PX)
            holder.cardView.setStrokeColor(ColorStateList.valueOf(colorRes))
            holder.icon.imageTintList = ColorStateList.valueOf(colorRes)
            holder.cardView.scaleX = SELECTED_SCALE
            holder.cardView.scaleY = SELECTED_SCALE
        } else {
            holder.cardView.setStrokeWidth(0)
            holder.icon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.light_grey))
            holder.cardView.scaleX = 1.0f
            holder.cardView.scaleY = 1.0f
        }

        if (server.platform == CastPlatform.MANUAL) {
            holder.moreButton.setImageResource(android.R.drawable.ic_menu_delete)
            holder.moreButton.visibility = View.VISIBLE
            holder.moreButton.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onDeleteClick(server)
            }
        } else {
            holder.moreButton.setImageResource(android.R.drawable.ic_menu_more)
            holder.moreButton.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onServerClick(server)
            // A row moved by a list update keeps its listener, so ask for the current position
            // instead of the one captured at bind time.
            val currentPosition = holder.adapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                setSelectedItem(currentPosition)
            }
        }
    }

    override fun getItemCount(): Int = servers.size

    fun submitList(newServers: List<Server>) {
        val diff = DiffUtil.calculateDiff(ServerDiff(servers, newServers, selectedItem))
        servers = newServers
        diff.dispatchUpdatesTo(this)
    }

    fun setSelectedItem(position: Int) {
        val previousItem = selectedItem
        selectedItem = position
        if (previousItem != -1) {
            notifyItemChanged(previousItem)
        }
        notifyItemChanged(selectedItem)
    }

    /**
     * Rows are keyed by [DiscoveryManager.identityOf]. The selection highlight belongs to an
     * index, not to a server, so a row moving onto or off the selected index must be rebound.
     */
    private class ServerDiff(
        private val oldServers: List<Server>,
        private val newServers: List<Server>,
        private val selectedItem: Int
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldServers.size

        override fun getNewListSize() = newServers.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            DiscoveryManager.identityOf(oldServers[oldItemPosition]) ==
                DiscoveryManager.identityOf(newServers[newItemPosition])

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldServers[oldItemPosition] == newServers[newItemPosition] &&
                (oldItemPosition == selectedItem) == (newItemPosition == selectedItem)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view as MaterialCardView
        val serverName: TextView = view.findViewById(R.id.serverName)
        val serverHost: TextView = view.findViewById(R.id.serverHost)
        val icon: ImageView = view.findViewById(R.id.serverIcon)
        val moreButton: ImageView = view.findViewById(R.id.moreButton)
    }

    private companion object {
        const val SELECTED_STROKE_WIDTH_PX = 4
        const val SELECTED_SCALE = 1.02f
    }
}
