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
            binding.tvName.text = session.profileName.ifBlank { "Roast #${session.id}" }
            binding.tvDate.text = TimeFormatter.formatDate(session.startTimeMs)
            binding.tvFav.visibility = if (session.isFavorite) View.VISIBLE else View.GONE

            // FC tag — time from roast start to first crack
            val fcStart = session.firstCrackStartMs
            if (fcStart != null) {
                binding.tagFc.text = "FC ${TimeFormatter.formatDuration(fcStart - session.startTimeMs)}"
                binding.tagFc.visibility = View.VISIBLE
            } else {
                binding.tagFc.visibility = View.GONE
            }

            // SC tag
            val sc = session.secondCrackDetectedMs
            if (sc != null) {
                binding.tagSc.text = "SC ${TimeFormatter.formatDuration(sc - session.startTimeMs)}"
                binding.tagSc.visibility = View.VISIBLE
            } else {
                binding.tagSc.visibility = View.GONE
            }

            // Cooling carry tag
            binding.tagCool.text = "CARRY"
            binding.tagCool.visibility =
                if (session.coolingStartedMs != null) View.VISIBLE else View.GONE

            binding.tvTotal.text = session.totalDurationMs
                ?.let { TimeFormatter.formatDuration(it) } ?: "—"
            binding.tvFcDur.text = session.firstCrackDurationMs
                ?.let { TimeFormatter.formatDuration(it) } ?: "—"
            binding.tvProfile.text = session.beanOrigin.ifBlank { session.profileName.ifBlank { "—" } }

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
