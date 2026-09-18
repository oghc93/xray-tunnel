package com.lite.xraylite.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lite.xraylite.R
import com.lite.xraylite.model.ConnectionType
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.util.PingTester

class ServerListAdapter(
    private val onSelect: (ServerConfig) -> Unit,
    private val onShare: (ServerConfig) -> Unit,
    private val onEdit: (ServerConfig) -> Unit,
    private val onDelete: (ServerConfig) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.VH>() {

    private val items = mutableListOf<ServerConfig>()
    private var activeId: String? = null
    private val pingCache = HashMap<String, Long>()

    fun submit(list: List<ServerConfig>, activeId: String?) {
        items.clear()
        items.addAll(list)
        this.activeId = activeId
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.dotActive)
        val name: TextView = view.findViewById(R.id.tvName)
        val detail: TextView = view.findViewById(R.id.tvDetail)
        val ping: TextView = view.findViewById(R.id.tvPing)
        val typeTag: TextView = view.findViewById(R.id.tvTypeTag)
        val btnShare: ImageButton = view.findViewById(R.id.btnShare)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = items[position]
        holder.name.text = cfg.name
        holder.detail.text = "${cfg.address}:${cfg.port}"
        holder.typeTag.text = "(${tagFor(cfg)})"
        holder.dot.setBackgroundResource(
            if (cfg.id == activeId) R.drawable.bar_active else R.drawable.bar_inactive
        )

        val cachedPing = pingCache[cfg.id]
        applyPing(holder, cachedPing)

        holder.itemView.setOnClickListener { onSelect(cfg) }
        holder.btnShare.setOnClickListener { onShare(cfg) }
        holder.btnEdit.setOnClickListener { onEdit(cfg) }
        holder.btnDelete.setOnClickListener { onDelete(cfg) }

        if (cachedPing == null) {
            PingTester.test(cfg) { success, ms ->
                holder.itemView.post {
                    val value = if (success) ms else -1L
                    pingCache[cfg.id] = value
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && items.getOrNull(pos)?.id == cfg.id) {
                        applyPing(holder, value)
                    }
                }
            }
        }
    }

    private fun tagFor(cfg: ServerConfig): String = when (cfg.type) {
        ConnectionType.SSH -> buildString {
            append("SSH")
            if (cfg.sshUseTls) append(" + SNI")
            if (cfg.sshPayload.isNotBlank()) append(" + Payload")
        }
        else -> "${cfg.type} + ${cfg.network.uppercase()}" + if (cfg.security == "tls") " + TLS" else ""
    }

    private fun applyPing(holder: VH, ms: Long?) {
        val ctx = holder.ping.context
        val (label, colorRes) = when {
            ms == null -> "-- ms" to R.color.og_text_dim
            ms < 0 -> "timeout" to R.color.og_red
            ms < 150 -> "${ms} ms" to R.color.og_green
            ms < 400 -> "${ms} ms" to R.color.og_gold
            else -> "${ms} ms" to R.color.og_red
        }
        holder.ping.text = label
        val bg = holder.ping.background
        if (bg is GradientDrawable) {
            bg.mutate()
            bg.setColor(ContextCompat.getColor(ctx, colorRes))
        }
    }

    override fun getItemCount() = items.size
}
