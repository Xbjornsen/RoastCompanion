package com.roastcompanion.ui.roast

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roastcompanion.databinding.FragmentCarryoverBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CarryoverFragment : DialogFragment() {

    private var _binding: FragmentCarryoverBinding? = null
    private val binding get() = _binding!!

    // Share the same ViewModel as RoastFragment
    private val viewModel: RoastViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCarryoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCarryoverDone.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.carryoverState.collect { state ->
                    state ?: return@collect
                    val remaining = state.remainingS
                    binding.tvCarryoverTimer.text = "%d:%02d".format(remaining / 60, remaining % 60)
                    binding.tvCarryoverColorLabel.text = state.colorLabel
                    binding.progressCarryover.progress = ((1f - state.progressFraction) * 100).toInt()

                    if (state.isDone) {
                        binding.tvCarryoverStatus.text = getString(com.roastcompanion.R.string.carryover_done)
                        binding.tvCarryoverTimer.text = "0:00"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
