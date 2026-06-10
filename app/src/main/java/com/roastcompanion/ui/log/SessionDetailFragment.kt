package com.roastcompanion.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.snackbar.Snackbar
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

    // Avoid clobbering in-progress typing when the session reloads
    private var notesLoaded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSessionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnSaveNotes.setOnClickListener {
            viewModel.saveNotes(binding.etNotes.text.toString())
            Snackbar.make(binding.root, "Notes saved", Snackbar.LENGTH_SHORT).show()
        }
        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }
        starViews().forEachIndexed { i, star ->
            star.setOnClickListener { viewModel.setRating(i + 1) }
        }

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
        binding.tvStart.text  = "Start     ${TimeFormatter.formatTimestamp(session.startTimeMs)}"
        binding.tvFcStart.text = "FC Start  ${session.firstCrackStartMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvFcEnd.text   = "FC End    ${session.firstCrackEndMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvSc.text      = "2C        ${session.secondCrackDetectedMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvCooling.text = "Cooling   ${session.coolingStartedMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvEnd.text     = "End       ${session.endTimeMs?.let { TimeFormatter.formatTimestamp(it) } ?: "—"}"
        binding.tvTotal.text   = "TOTAL  ${session.totalDurationMs?.let { TimeFormatter.formatDuration(it) } ?: "—"}"

        if (!notesLoaded) {
            binding.etNotes.setText(session.notes)
            notesLoaded = true
        }

        val ctx = requireContext()
        val amber = ContextCompat.getColor(ctx, R.color.lab_amber)
        val dim = ContextCompat.getColor(ctx, R.color.lab_text_dim)

        binding.btnFavorite.text = if (session.isFavorite) "★" else "☆"
        binding.btnFavorite.setTextColor(if (session.isFavorite) amber else dim)

        starViews().forEachIndexed { i, star ->
            val filled = i < session.rating
            star.text = if (filled) "★" else "☆"
            star.setTextColor(if (filled) amber else dim)
        }
    }

    private fun starViews() = listOf(
        binding.star1, binding.star2, binding.star3, binding.star4, binding.star5
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
