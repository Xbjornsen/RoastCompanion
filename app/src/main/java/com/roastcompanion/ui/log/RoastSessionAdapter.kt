package com.roastcompanion.ui.log

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.databinding.ItemRoastSessionBinding
import com.roastcompanion.util.TimeFormatter

class RoastSessionAdapter(
    private val onClick: (RoastSession) -> Unit
) : ListAdapter<RoastSession, RoastSessionAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemRoastSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: RoastSession) {
            binding.tvDate.text = TimeFormatter.formatDate(session.startTimeMs)
            binding.tvDuration.text = session.totalDurationMs
                ?.let { TimeFormatter.formatDuration(it) } ?: "—"
            binding.tvFcTime.text = session.firstCrackStartMs
                ?.let { "FC @ ${TimeFormatter.formatTimestamp(it)}" } ?: "No FC logged"
            binding.tvScBadge.visibility =
                if (session.secondCrackDetectedMs != null) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(session) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemRoastSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RoastSession>() {
            override fun areItemsTheSame(a: RoastSession, b: RoastSession) = a.id == b.id
            override fun areContentsTheSame(a: RoastSession, b: RoastSession) = a == b
        }
    }
}
