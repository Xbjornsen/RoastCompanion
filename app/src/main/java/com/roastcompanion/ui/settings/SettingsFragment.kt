package com.roastcompanion.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.roastcompanion.BuildConfig
import com.roastcompanion.R
import com.roastcompanion.data.model.RoasterProfiles
import com.roastcompanion.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    // Prevent slider listeners from firing during programmatic updates
    private var updatingFromVm = false

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.exportHistory(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importHistory(it) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sliderThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingFromVm) viewModel.setThresholdMultiplier(value)
            binding.tvThresholdValue.text = "×%.1f".format(value)
        }
        binding.sliderFcQuiet.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingFromVm) viewModel.setFcQuietPeriodS(value.toInt())
            binding.tvFcQuietValue.text = "${value.toInt()}s"
        }
        binding.sliderMinFcTime.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingFromVm) viewModel.setMinFcTimeMin(value.toInt())
            binding.tvMinFcTimeValue.text = "${value.toInt()} min"
        }
        binding.sliderCarryover.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingFromVm) viewModel.setCarryoverDurationS(value.toInt())
            binding.tvCarryoverValue.text = "${value.toInt()}s"
        }
        binding.sliderMinFc.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingFromVm) viewModel.setMinTransientsFc(value.toInt())
            binding.tvMinFcValue.text = "${value.toInt()}"
        }
        binding.sliderMinSc.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingFromVm) viewModel.setMinTransientsSc(value.toInt())
            binding.tvMinScValue.text = "${value.toInt()}"
        }
        binding.switchAlarmSound.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromVm) viewModel.setAlarmSoundEnabled(checked)
        }
        binding.switchVibration.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromVm) viewModel.setVibrationEnabled(checked)
        }
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            if (!updatingFromVm) viewModel.setKeepScreenOn(checked)
        }
        binding.btnResetDefaults.setOnClickListener {
            viewModel.resetDefaults()
        }
        binding.btnGuide.setOnClickListener {
            findNavController().navigate(R.id.guideFragment)
        }

        binding.btnExport.setOnClickListener {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exportLauncher.launch("roastcompanion-$date.csv")
        }
        binding.btnImport.setOnClickListener {
            // CSV mime types vary by file manager — accept anything, parser validates
            importLauncher.launch(arrayOf("*/*"))
        }
        binding.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        binding.btnUpdate.setOnClickListener { onUpdateRowTapped() }

        binding.cardRoaster.setOnClickListener { showRoasterPicker() }
        binding.chipCelsius.setOnClickListener { if (!updatingFromVm) viewModel.setTempUnitCelsius(true) }
        binding.chipFahrenheit.setOnClickListener { if (!updatingFromVm) viewModel.setTempUnitCelsius(false) }

        binding.tvCrumb.text = "SETTINGS · V${BuildConfig.VERSION_NAME}"
        binding.tvFooterVersion.text = "ROASTCOMPANION · ${BuildConfig.VERSION_NAME}"
        binding.tvUpdateSub.text = "Version ${BuildConfig.VERSION_NAME}"

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.thresholdMultiplier.collect { v ->
                        updatingFromVm = true
                        binding.sliderThreshold.value = v
                        binding.tvThresholdValue.text = "×%.1f".format(v)
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.fcQuietPeriodS.collect { v ->
                        updatingFromVm = true
                        binding.sliderFcQuiet.value = v.toFloat()
                        binding.tvFcQuietValue.text = "${v}s"
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.minFcTimeMin.collect { v ->
                        updatingFromVm = true
                        binding.sliderMinFcTime.value = v.toFloat()
                        binding.tvMinFcTimeValue.text = "$v min"
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.carryoverDurationS.collect { v ->
                        updatingFromVm = true
                        binding.sliderCarryover.value = v.toFloat()
                        binding.tvCarryoverValue.text = "${v}s"
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.minTransientsFc.collect { v ->
                        updatingFromVm = true
                        binding.sliderMinFc.value = v.toFloat()
                        binding.tvMinFcValue.text = "$v"
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.minTransientsSc.collect { v ->
                        updatingFromVm = true
                        binding.sliderMinSc.value = v.toFloat()
                        binding.tvMinScValue.text = "$v"
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.alarmSoundEnabled.collect { v ->
                        updatingFromVm = true
                        binding.switchAlarmSound.isChecked = v
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.vibrationEnabled.collect { v ->
                        updatingFromVm = true
                        binding.switchVibration.isChecked = v
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.keepScreenOn.collect { v ->
                        updatingFromVm = true
                        binding.switchKeepScreenOn.isChecked = v
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.messages.collect { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.updateState.collect { renderUpdateState(it) }
                }
                launch {
                    viewModel.roasterProfile.collect { profileName ->
                        updatingFromVm = true
                        val profile = RoasterProfiles.byName(profileName) ?: RoasterProfiles.all.last()
                        binding.tvRoasterName.text = profile.name
                        binding.tvRoasterSub.text = buildString {
                            if (profile.type.isNotEmpty()) { append(profile.type); append(" · ") }
                            append("${profile.carryoverSecs}s carryover")
                        }
                        updatingFromVm = false
                    }
                }
                launch {
                    viewModel.tempUnitCelsius.collect { isCelsius ->
                        updatingFromVm = true
                        val amber = ContextCompat.getColor(requireContext(), R.color.lab_amber)
                        val dim = ContextCompat.getColor(requireContext(), R.color.lab_text_dim)
                        binding.chipCelsius.setTextColor(if (isCelsius) amber else dim)
                        binding.chipFahrenheit.setTextColor(if (!isCelsius) amber else dim)
                        updatingFromVm = false
                    }
                }
            }
        }
    }

    private fun onUpdateRowTapped() {
        when (viewModel.updateState.value) {
            is UpdateState.Available -> viewModel.downloadAndInstallUpdate()
            is UpdateState.Checking, is UpdateState.Downloading -> Unit
            else -> viewModel.checkForUpdate()
        }
    }

    private fun renderUpdateState(state: UpdateState) {
        val amber = ContextCompat.getColor(requireContext(), R.color.lab_amber)
        val text = ContextCompat.getColor(requireContext(), R.color.lab_text)
        val current = "Version ${BuildConfig.VERSION_NAME}"

        binding.tvUpdateTitle.setTextColor(text)
        when (state) {
            is UpdateState.Idle -> {
                binding.tvUpdateTitle.text = getString(R.string.check_updates)
                binding.tvUpdateSub.text = current
            }
            is UpdateState.Checking -> {
                binding.tvUpdateTitle.text = getString(R.string.check_updates)
                binding.tvUpdateSub.text = "Checking…"
            }
            is UpdateState.UpToDate -> {
                binding.tvUpdateTitle.text = getString(R.string.check_updates)
                binding.tvUpdateSub.text = "$current — up to date"
            }
            is UpdateState.Available -> {
                binding.tvUpdateTitle.text = getString(R.string.update_install)
                binding.tvUpdateTitle.setTextColor(amber)
                binding.tvUpdateSub.text = "Version ${state.info.versionName} available"
            }
            is UpdateState.Downloading -> {
                binding.tvUpdateTitle.text = getString(R.string.update_install)
                binding.tvUpdateSub.text = "Downloading ${state.versionName}…"
            }
            is UpdateState.Failed -> {
                binding.tvUpdateTitle.text = getString(R.string.check_updates)
                binding.tvUpdateSub.text = state.message
            }
        }
    }

    private fun showRoasterPicker() {
        val names = RoasterProfiles.all.map { it.name }.toTypedArray()
        val currentName = viewModel.roasterProfile.value
        val currentIdx = RoasterProfiles.all.indexOfFirst { it.name == currentName }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Roaster")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                viewModel.selectRoaster(RoasterProfiles.all[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        val count = viewModel.sessionCount.value
        if (count == 0) {
            Snackbar.make(binding.root, "No roast history to delete", Snackbar.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_all_confirm_title)
            .setMessage(getString(R.string.delete_all_confirm_msg, count))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteAllHistory() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
