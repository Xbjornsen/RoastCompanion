package com.roastcompanion.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roastcompanion.R
import com.roastcompanion.data.db.entity.RoastSession
import com.roastcompanion.databinding.FragmentSessionDetailBinding
import com.roastcompanion.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SessionDetailFragment : Fragment() {

    private var _binding: FragmentSessionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SessionDetailViewModel by viewModels()
    private val args: SessionDetailFragmentArgs by navArgs()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSessionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.fabEditNotes.setOnClickListener { showNotesDialog() }

        viewModel.loadSession(args.sessionId)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.session.collect { session ->
                    session?.let { bindSession(it) }
                }
            }
        }
    }

    private fun bindSession(session: RoastSession) {
        binding.tvSessionDate.text = TimeFormatter.formatDate(session.startTimeMs)
        binding.tvStart.text  = "Start:    ${TimeFormatter.formatTimestamp(session.startTimeMs)}"
        binding.tvFcStart.text = "FC Start: ${session.firstCrackStartMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvFcEnd.text   = "FC End:   ${session.firstCrackEndMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvSc.text      = "2C:       ${session.secondCrackDetectedMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvCooling.text = "Cooling:  ${session.coolingStartedMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvEnd.text     = "End:      ${session.endTimeMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvTotal.text   = "Total: ${session.totalDurationMs?.let { TimeFormatter.formatDuration(it) } ?: "—"}"
        binding.tvNotes.text   = session.notes.ifBlank { getString(R.string.notes_hint) }
    }

    private fun showNotesDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.notes_hint)
            setText(viewModel.session.value?.notes ?: "")
            setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_notes)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> viewModel.saveNotes(input.text.toString()) }
            .setNegativeButton(R.string.perm_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
