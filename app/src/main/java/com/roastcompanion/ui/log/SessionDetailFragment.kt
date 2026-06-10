package com.roastcompanion.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SessionDetailFragment : Fragment() {

    private var _binding: FragmentSessionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SessionDetailViewModel by viewModels()
    private val args: SessionDetailFragmentArgs by navArgs()

    private var notesLoaded = false
    private var tempsLoaded = false
    private var beanLoaded = false
    private var lastTempUnit: Boolean? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSessionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.btnSaveNotes.setOnClickListener {
            viewModel.saveNotes(binding.etNotes.text.toString())
            binding.etNotes.clearFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
            val snack = Snackbar.make(binding.root, "Notes saved", Snackbar.LENGTH_SHORT)
            snack.setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.lab_surface))
            snack.setTextColor(ContextCompat.getColor(requireContext(), R.color.lab_text))
            snack.show()
        }

        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }
        starViews().forEachIndexed { i, star ->
            star.setOnClickListener { viewModel.setRating(i + 1) }
        }

        // Temp fields: save on focus lost, converting to °C if needed
        binding.etFcStartTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etFcStartTemp.text.toString()) { viewModel.setFcStartTemp(it) }
        }
        binding.etFcEndTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etFcEndTemp.text.toString()) { viewModel.setFcEndTemp(it) }
        }
        binding.etScTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etScTemp.text.toString()) { viewModel.setScTemp(it) }
        }

        // Bean origin: save on focus lost
        binding.etBeanOrigin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveBeanInfo()
        }

        // Bean type chips
        binding.chipSingleOrigin.setOnClickListener {
            viewModel.setBeanInfo(binding.etBeanOrigin.text.toString(), isBlend = false)
        }
        binding.chipBlend.setOnClickListener {
            viewModel.setBeanInfo(binding.etBeanOrigin.text.toString(), isBlend = true)
        }

        viewModel.loadSession(args.sessionId)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.session, viewModel.tempUnitCelsius) { s, c -> s to c }
                        .collect { (session, isCelsius) -> session?.let { bindSession(it, isCelsius) } }
                }
            }
        }
    }

    private fun saveTempField(text: String, setter: (Float?) -> Unit) {
        val value = text.trim().toFloatOrNull()
        val isCelsius = viewModel.tempUnitCelsius.value
        val tempC = when {
            value == null -> null
            isCelsius -> value
            else -> (value - 32f) * 5f / 9f
        }
        setter(tempC)
    }

    private fun saveBeanInfo() {
        val session = viewModel.session.value ?: return
        viewModel.setBeanInfo(binding.etBeanOrigin.text.toString(), session.isBlend)
    }

    private fun bindSession(session: RoastSession, isCelsius: Boolean) {
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

        // Temps: load on first render, and re-render if unit changes
        val unitChanged = lastTempUnit != isCelsius
        lastTempUnit = isCelsius
        val unitStr = if (isCelsius) "°C" else "°F"
        binding.etFcStartTemp.hint = "—$unitStr"
        binding.etFcEndTemp.hint   = "—$unitStr"
        binding.etScTemp.hint      = "—$unitStr"
        if (!tempsLoaded || unitChanged) {
            binding.etFcStartTemp.setText(session.fcStartTempC.displayIn(isCelsius))
            binding.etFcEndTemp.setText(session.fcEndTempC.displayIn(isCelsius))
            binding.etScTemp.setText(session.scTempC.displayIn(isCelsius))
            tempsLoaded = true
        }

        // Bean info
        if (!beanLoaded) {
            binding.etBeanOrigin.setText(session.beanOrigin)
            beanLoaded = true
        }
        renderBeanTypeChips(session.isBlend)

        // Favourite + rating
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

    private fun renderBeanTypeChips(isBlend: Boolean) {
        val ctx = requireContext()
        val amber = ContextCompat.getColor(ctx, R.color.lab_amber)
        val dim = ContextCompat.getColor(ctx, R.color.lab_text_dim)
        binding.chipSingleOrigin.setTextColor(if (!isBlend) amber else dim)
        binding.chipBlend.setTextColor(if (isBlend) amber else dim)
    }

    private fun Float?.displayIn(isCelsius: Boolean): String {
        this ?: return ""
        return if (isCelsius) "%.0f".format(this) else "%.0f".format(this * 9f / 5f + 32f)
    }

    private fun starViews() = listOf(
        binding.star1, binding.star2, binding.star3, binding.star4, binding.star5
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
