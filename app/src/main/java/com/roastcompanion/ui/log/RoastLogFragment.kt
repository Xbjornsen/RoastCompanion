package com.roastcompanion.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        setupSwipeToDelete()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessions.collect { sessions ->
                    adapter.submitList(sessions)
                    binding.layoutEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerSessions.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun setupSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val session = adapter.currentList[viewHolder.adapterPosition]
                viewModel.deleteSession(session)
                Snackbar.make(binding.root, R.string.session_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) { viewModel.undoDelete() }
                    .show()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerSessions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
