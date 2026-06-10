package com.roastcompanion.ui.settings

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
import com.roastcompanion.R
import com.roastcompanion.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    // Prevent slider listeners from firing during programmatic updates
    private var updatingFromVm = false

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
        binding.btnResetDefaults.setOnClickListener {
            viewModel.resetDefaults()
        }
        binding.btnGuide.setOnClickListener {
            findNavController().navigate(R.id.guideFragment)
        }

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
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
