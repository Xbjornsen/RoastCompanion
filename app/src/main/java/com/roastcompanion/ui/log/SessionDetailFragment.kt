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
    private var nameLoaded = false
    private var weightLoaded = false
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

        binding.etRoastName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setRoastName(binding.etRoastName.text.toString().trim())
        }

        binding.etFcStartTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etFcStartTemp.text.toString()) { viewModel.setFcStartTemp(it) }
        }
        binding.etFcEndTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etFcEndTemp.text.toString()) { viewModel.setFcEndTemp(it) }
        }
        binding.etScTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etScTemp.text.toString()) { viewModel.setScTemp(it) }
        }
        binding.etChargeTemp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTempField(binding.etChargeTemp.text.toString()) { viewModel.setChargeTemp(it) }
        }

        binding.etBeanOrigin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveBeanInfo()
        }

        binding.chipSingleOrigin.setOnClickListener {
            viewModel.setBeanInfo(binding.etBeanOrigin.text.toString(), isBlend = false)
        }
        binding.chipBlend.setOnClickListener {
            viewModel.setBeanInfo(binding.etBeanOrigin.text.toString(), isBlend = true)
        }

        binding.etGreenWeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveWeightFields()
        }
        binding.etRoastedWeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveWeightFields()
        }

        roastLevelChips().forEach { (chip, level) ->
            chip.setOnClickListener { viewModel.setRoastLevel(level) }
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

    private fun saveWeightFields() {
        val greenG = binding.etGreenWeight.text.toString().trim().toFloatOrNull()
        val roastedG = binding.etRoastedWeight.text.toString().trim().toFloatOrNull()
        viewModel.setWeight(greenG, roastedG)
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

        // Derived stats
        val fcFromStart = session.firstCrackStartMs?.let { it - session.startTimeMs }
        binding.tvFcFromStart.text = fcFromStart?.let { TimeFormatter.formatDuration(it) } ?: "—"

        val devTime = if (session.firstCrackEndMs != null && session.endTimeMs != null)
            session.endTimeMs - session.firstCrackEndMs else null
        binding.tvDevTime.text = devTime?.let { TimeFormatter.formatDuration(it) } ?: "—"

        val dtr = if (devTime != null && (session.totalDurationMs ?: 0L) > 0)
            devTime.toFloat() / session.totalDurationMs!! * 100f else null
        binding.tvDtr.text = dtr?.let { "%.0f%%".format(it) } ?: "—"

        if (!notesLoaded) {
            binding.etNotes.setText(session.notes)
            notesLoaded = true
        }

        if (!nameLoaded) {
            binding.etRoastName.setText(session.profileName)
            nameLoaded = true
        }

        val unitChanged = lastTempUnit != isCelsius
        lastTempUnit = isCelsius
        val unitStr = if (isCelsius) "°C" else "°F"
        binding.etFcStartTemp.hint = "—$unitStr"
        binding.etFcEndTemp.hint   = "—$unitStr"
        binding.etScTemp.hint      = "—$unitStr"
        binding.etChargeTemp.hint  = "—$unitStr"
        if (!tempsLoaded || unitChanged) {
            binding.etFcStartTemp.setText(session.fcStartTempC.displayIn(isCelsius))
            binding.etFcEndTemp.setText(session.fcEndTempC.displayIn(isCelsius))
            binding.etScTemp.setText(session.scTempC.displayIn(isCelsius))
            binding.etChargeTemp.setText(session.chargeTempC.displayIn(isCelsius))
            tempsLoaded = true
        }

        if (!beanLoaded) {
            binding.etBeanOrigin.setText(session.beanOrigin)
            beanLoaded = true
        }
        renderBeanTypeChips(session.isBlend)

        if (!weightLoaded) {
            binding.etGreenWeight.setText(session.greenWeightG?.let { "%.0f".format(it) } ?: "")
            binding.etRoastedWeight.setText(session.roastedWeightG?.let { "%.0f".format(it) } ?: "")
            weightLoaded = true
        }
        renderWeightLoss(session.greenWeightG, session.roastedWeightG)
        renderRoastLevelChips(session.roastLevel)

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

    private fun renderRoastLevelChips(selected: String) {
        val ctx = requireContext()
        val amber = ContextCompat.getColor(ctx, R.color.lab_amber)
        val dim = ContextCompat.getColor(ctx, R.color.lab_text_dim)
        roastLevelChips().forEach { (chip, level) ->
            chip.setTextColor(if (level == selected) amber else dim)
        }
    }

    private fun renderWeightLoss(greenG: Float?, roastedG: Float?) {
        if (greenG != null && roastedG != null && greenG > 0) {
            val loss = (1f - roastedG / greenG) * 100f
            binding.tvWeightLoss.text = "%.1f%%".format(loss)
        } else {
            binding.tvWeightLoss.text = "—"
        }
    }

    private fun roastLevelChips() = listOf(
        binding.chipCity to "City",
        binding.chipCityPlus to "City+",
        binding.chipFullCity to "Full City",
        binding.chipFullCityPlus to "Full City+",
        binding.chipVienna to "Vienna",
        binding.chipFrench to "French"
    )

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
