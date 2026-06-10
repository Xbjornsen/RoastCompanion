package com.roastcompanion.ui.log

import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.roastcompanion.R
import com.roastcompanion.databinding.FragmentRoastLogBinding
import com.roastcompanion.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RoastLogFragment : Fragment() {

    private var _binding: FragmentRoastLogBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RoastLogViewModel by viewModels()
    private lateinit var adapter: RoastSessionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoastLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RoastSessionAdapter { session ->
            val action = RoastLogFragmentDirections.actionLogToDetail(session.id)
            findNavController().navigate(action)
        }

        binding.recyclerSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSessions.adapter = adapter

        setupSearch()
        setupFilterChips()
        setupSwipeToDelete()
        observeViewModel()
    }

    private fun setupSearch() {
        binding.etSearch.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }
    }

    private fun setupFilterChips() {
        val chips = mapOf(
            binding.chipAll to SessionFilter.ALL,
            binding.chipWeek to SessionFilter.THIS_WEEK,
            binding.chipFc to SessionFilter.FC_ONLY
        )
        chips.forEach { (chip, filter) ->
            chip.setOnClickListener { viewModel.setFilter(filter) }
        }
    }

    private fun renderFilterChips(active: SessionFilter) {
        val ctx = requireContext()
        val amber = ContextCompat.getColor(ctx, R.color.lab_amber)
        val muted = ContextCompat.getColor(ctx, R.color.lab_text_muted)

        listOf(
            Triple(binding.chipAll, SessionFilter.ALL, "All"),
            Triple(binding.chipWeek, SessionFilter.THIS_WEEK, "This Week"),
            Triple(binding.chipFc, SessionFilter.FC_ONLY, "FC Only")
        ).forEach { (chip, filter, _) ->
            val selected = filter == active
            chip.isSelected = selected
            chip.setTextColor(if (selected) amber else muted)
            chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.sessions.collect { sessions ->
                        adapter.submitList(sessions)
                        binding.layoutEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                        binding.recyclerSessions.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                        binding.tvSwipeHint.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                    }
                }

                launch {
                    viewModel.stats.collect { stats ->
                        binding.tvStatMonth.text = stats.thisMonth.toString()
                        binding.tvStatAvgFc.text = stats.avgFcMs
                            ?.let { TimeFormatter.formatDuration(it) } ?: "—"
                        binding.tvStatTotal.text = stats.total.toString()
                        binding.tvCrumb.text = "HISTORY · ${stats.total} SESSIONS"
                    }
                }

                launch {
                    viewModel.filter.collect { renderFilterChips(it) }
                }
            }
        }
    }

    private fun setupSwipeToDelete() {
        val deleteBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_swipe_delete)!!
        val deleteIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)!!

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                @Suppress("DEPRECATION")
                val session = adapter.currentList[viewHolder.adapterPosition]
                viewModel.deleteSession(session)
                Snackbar.make(binding.root, R.string.session_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) { viewModel.undoDelete() }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isActive: Boolean
            ) {
                val item = vh.itemView
                if (dX < 0) {
                    // Red rounded background behind the swiped row
                    deleteBg.setBounds(
                        item.right + dX.toInt(),
                        item.top,
                        item.right,
                        item.bottom
                    )
                    deleteBg.draw(c)

                    // Trash icon, vertically centred near the right edge
                    val iconSize = (24 * rv.resources.displayMetrics.density).toInt()
                    val iconMargin = (22 * rv.resources.displayMetrics.density).toInt()
                    val iconTop = item.top + (item.height - iconSize) / 2
                    deleteIcon.setBounds(
                        item.right - iconMargin - iconSize,
                        iconTop,
                        item.right - iconMargin,
                        iconTop + iconSize
                    )
                    deleteIcon.draw(c)
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerSessions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
