package com.roastcompanion.ui.roast

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.roastcompanion.R
import com.roastcompanion.audio.RoastPhase
import com.roastcompanion.databinding.FragmentRoastBinding
import com.roastcompanion.util.PermissionHelper
import com.roastcompanion.util.TimeFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RoastFragment : Fragment() {

    private var _binding: FragmentRoastBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RoastViewModel by viewModels()

    // Rolling RMS history for the level meter (200 entries ≈ 10s at 20fps)
    private val rmsHistory = ArrayDeque<Float>(200)

    private var alarmPlayer: MediaPlayer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[android.Manifest.permission.RECORD_AUDIO] == true) {
            startRoast()
        } else {
            Snackbar.make(binding.root, R.string.perm_denied_snackbar, Snackbar.LENGTH_LONG)
                .setAction(R.string.perm_open_settings) { openAppSettings() }
                .show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart()
        setupButtons()
        observeViewModel()
    }

    private fun setupChart() {
        binding.chartLevel.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            axisLeft.isEnabled = false
            axisRight.isEnabled = false
            xAxis.isEnabled = false
            setScaleEnabled(false)
            setTouchEnabled(false)
            setViewPortOffsets(0f, 0f, 0f, 0f)
        }
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.isSessionActive()) {
                viewModel.onStopRoast(requireContext())
            } else {
                requestPermissionsAndStart()
            }
        }

        binding.btnStartCooling.setOnClickListener {
            viewModel.onStartCooling(requireContext())
            findNavController().navigate(R.id.carryoverFragment)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.sessionTimerMs.collect { ms ->
                        binding.tvTimer.text = TimeFormatter.formatElapsed(ms)
                    }
                }

                launch {
                    viewModel.phase.collect { phase ->
                        updatePhaseUi(phase)
                    }
                }

                launch {
                    viewModel.rmsLevel.collect { rms ->
                        updateLevelMeter(rms)
                    }
                }

                launch {
                    viewModel.fcStartMs.collect { ms ->
                        binding.tvFcStart.text = ms?.let { TimeFormatter.formatTimestamp(it) }
                            ?: getString(R.string.not_detected)
                    }
                }

                launch {
                    viewModel.fcEndMs.collect { ms ->
                        binding.tvFcEnd.text = ms?.let { TimeFormatter.formatTimestamp(it) }
                            ?: getString(R.string.not_detected)
                    }
                }

                launch {
                    combine(viewModel.fcStartMs, viewModel.fcEndMs) { start, end ->
                        if (start != null && end != null) TimeFormatter.formatDuration(end - start)
                        else getString(R.string.not_detected)
                    }.collect { text ->
                        binding.tvFcDuration.text = text
                    }
                }

                launch {
                    viewModel.scDetectedMs.collect { ms ->
                        binding.tvScDetected.text = ms?.let { TimeFormatter.formatTimestamp(it) }
                            ?: getString(R.string.not_detected)
                    }
                }

                launch {
                    viewModel.alerts.collect { alert ->
                        handleAlert(alert)
                    }
                }
            }
        }
    }

    private fun updatePhaseUi(phase: RoastPhase) {
        binding.tvPhase.text = when (phase) {
            RoastPhase.IDLE                 -> getString(R.string.phase_idle)
            RoastPhase.MONITORING           -> getString(R.string.phase_listening)
            RoastPhase.FIRST_CRACK_ACTIVE   -> getString(R.string.phase_first_crack)
            RoastPhase.FIRST_CRACK_COMPLETE -> getString(R.string.phase_first_crack_done)
            RoastPhase.SECOND_CRACK_ACTIVE  -> getString(R.string.phase_second_crack)
            RoastPhase.COOLING              -> getString(R.string.phase_cooling)
        }

        val isActive = phase != RoastPhase.IDLE
        binding.btnStartStop.text = if (isActive) getString(R.string.stop_roast) else getString(R.string.start_roast)

        binding.btnStartCooling.isEnabled = phase == RoastPhase.FIRST_CRACK_COMPLETE ||
                phase == RoastPhase.SECOND_CRACK_ACTIVE
    }

    private fun updateLevelMeter(rms: Float) {
        if (rmsHistory.size >= 200) rmsHistory.removeFirst()
        rmsHistory.addLast(rms)

        val entries = rmsHistory.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(requireContext(), R.color.brown_400)
            setDrawValues(false)
        }
        binding.chartLevel.data = BarData(dataSet).apply { barWidth = 1f }
        binding.chartLevel.invalidate()
    }

    private fun handleAlert(alert: RoastAlert) {
        when (alert) {
            is RoastAlert.FirstCrackDetected -> {
                animateCardBackground(binding.cardFirstCrack,
                    ContextCompat.getColor(requireContext(), R.color.amber_100))
                vibrate(longArrayOf(0, 200, 100, 200))
                Snackbar.make(binding.root, R.string.alert_fc_detected, Snackbar.LENGTH_SHORT).show()
            }
            is RoastAlert.FirstCrackComplete -> {
                Snackbar.make(binding.root, R.string.alert_fc_complete, Snackbar.LENGTH_SHORT).show()
            }
            is RoastAlert.SecondCrackDetected -> {
                animateCardBackground(binding.cardSecondCrack,
                    ContextCompat.getColor(requireContext(), R.color.deep_orange_100))
                vibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                playAlarm()
                Snackbar.make(binding.root, R.string.alert_sc_detected, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.action_start_cooling) {
                        viewModel.onStartCooling(requireContext())
                        findNavController().navigate(R.id.carryoverFragment)
                    }.show()
            }
        }
    }

    private fun animateCardBackground(card: View, toColor: Int) {
        val fromColor = Color.WHITE
        ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = 600
            addUpdateListener { animator ->
                (card as? com.google.android.material.card.MaterialCardView)
                    ?.setCardBackgroundColor(animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requireContext().getSystemService<VibratorManager>()
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService<Vibrator>()?.vibrate(pattern, -1)
        }
    }

    private fun playAlarm() {
        alarmPlayer?.release()
        // Requires res/raw/alarm_sound.wav — place a WAV file there before building
        try {
            alarmPlayer = MediaPlayer.create(requireContext(), R.raw.alarm_sound)
            alarmPlayer?.start()
        } catch (_: Exception) {
            // No alarm file placed yet — fail silently
        }
    }

    private fun requestPermissionsAndStart() {
        if (PermissionHelper.hasRecordAudio(requireContext())) {
            startRoast()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_name)
                .setMessage(R.string.perm_mic_rationale)
                .setPositiveButton(R.string.perm_allow) { _, _ ->
                    permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                }
                .setNegativeButton(R.string.perm_cancel, null)
                .show()
        }
    }

    private fun startRoast() {
        viewModel.onStartRoast(requireContext())
    }

    private fun openAppSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        alarmPlayer?.release()
        alarmPlayer = null
        _binding = null
    }
}
