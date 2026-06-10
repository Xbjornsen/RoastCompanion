package com.roastcompanion.ui.roast

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.roastcompanion.R
import com.roastcompanion.audio.RoastPhase
import com.roastcompanion.data.db.entity.RoastSession
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
            axisLeft.apply {
                isEnabled = false
                axisMinimum = 0f
            }
            axisRight.apply {
                isEnabled = false
                axisMinimum = 0f
            }
            xAxis.isEnabled = false
            setScaleEnabled(false)
            setTouchEnabled(false)
            setViewPortOffsets(0f, 4f, 0f, 0f)
            setBackgroundColor(Color.TRANSPARENT)
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
            if (!it.isEnabled) return@setOnClickListener
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

                launch {
                    combine(viewModel.keepScreenOn, viewModel.phase) { keep, phase ->
                        keep && phase != RoastPhase.IDLE
                    }.collect { keepOn ->
                        val window = requireActivity().window
                        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                launch {
                    viewModel.referenceRoast.collect { renderReference(it) }
                }
            }
        }
    }

    private fun renderReference(ref: RoastSession?) {
        if (ref == null) {
            binding.cardReference.visibility = View.GONE
            return
        }
        binding.cardReference.visibility = View.VISIBLE
        binding.tvReferenceName.text =
            "★ " + ref.profileName.ifBlank { TimeFormatter.formatDate(ref.startTimeMs) }

        val targets = listOfNotNull(
            ref.firstCrackStartMs?.let { "FC @ ${TimeFormatter.formatDuration(it - ref.startTimeMs)}" },
            ref.secondCrackDetectedMs?.let { "SC @ ${TimeFormatter.formatDuration(it - ref.startTimeMs)}" },
            ref.totalDurationMs?.let { "END @ ${TimeFormatter.formatDuration(it)}" }
        )
        binding.tvReferenceTargets.text =
            if (targets.isEmpty()) "No crack times recorded" else targets.joinToString("  ·  ")
    }

    /** How this roast's first crack compares to the favourited reference. */
    private fun fcDeltaVsReference(): String? {
        val ref = viewModel.referenceRoast.value ?: return null
        val refFcMs = ref.firstCrackStartMs?.minus(ref.startTimeMs) ?: return null
        val diff = viewModel.sessionTimerMs.value - refFcMs
        if (kotlin.math.abs(diff) < 5_000) return "right on your reference"
        val dur = TimeFormatter.formatDuration(kotlin.math.abs(diff))
        return if (diff < 0) "$dur earlier than your reference" else "$dur later than your reference"
    }

    private fun updatePhaseUi(phase: RoastPhase) {
        val ctx = requireContext()
        binding.tvPhase.text = when (phase) {
            RoastPhase.IDLE                 -> getString(R.string.phase_idle)
            RoastPhase.MONITORING           -> getString(R.string.phase_listening)
            RoastPhase.FIRST_CRACK_ACTIVE   -> getString(R.string.phase_first_crack)
            RoastPhase.FIRST_CRACK_COMPLETE -> getString(R.string.phase_first_crack_done)
            RoastPhase.SECOND_CRACK_ACTIVE  -> getString(R.string.phase_second_crack)
            RoastPhase.COOLING              -> getString(R.string.phase_cooling)
        }
        binding.tvCrumb.text = "ROAST · ${binding.tvPhase.text}"

        // Phase pill colour reflects current phase
        val pillColor = when (phase) {
            RoastPhase.IDLE                 -> R.color.lab_text_dim
            RoastPhase.SECOND_CRACK_ACTIVE  -> R.color.lab_red
            RoastPhase.COOLING              -> R.color.lab_mint
            else                            -> R.color.lab_amber
        }
        val tint = ContextCompat.getColor(ctx, pillColor)
        binding.tvPhase.setTextColor(tint)
        binding.phaseDot.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)

        // FC stripe lights up when FC is active or complete
        binding.fcStripe.setBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (phase == RoastPhase.FIRST_CRACK_ACTIVE ||
                    phase == RoastPhase.FIRST_CRACK_COMPLETE ||
                    phase == RoastPhase.SECOND_CRACK_ACTIVE ||
                    phase == RoastPhase.COOLING
                ) R.color.lab_amber
                else R.color.lab_border_strong
            )
        )
        // SC stripe lights up red when SC reached
        binding.scStripe.setBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (phase == RoastPhase.SECOND_CRACK_ACTIVE || phase == RoastPhase.COOLING)
                    R.color.lab_red
                else R.color.lab_border_strong
            )
        )

        val isActive = phase != RoastPhase.IDLE
        binding.btnStartStop.text =
            if (isActive) getString(R.string.stop_roast) else getString(R.string.start_roast)

        val coolEnabled = phase == RoastPhase.FIRST_CRACK_COMPLETE ||
                phase == RoastPhase.SECOND_CRACK_ACTIVE
        binding.btnStartCooling.isEnabled = coolEnabled
        binding.btnStartCooling.alpha = if (coolEnabled) 1f else 0.5f
    }

    private fun updateLevelMeter(rms: Float) {
        if (rmsHistory.size >= 200) rmsHistory.removeFirst()
        rmsHistory.addLast(rms)

        // Display a friendly approximate dBFS value
        binding.tvMicValue.text = formatDb(rms)

        val lineColor = ContextCompat.getColor(requireContext(), R.color.lab_amber)
        val entries = rmsHistory.mapIndexed { i, v -> Entry(i.toFloat(), v) }

        val fillDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(120,
                    Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Color.TRANSPARENT
            )
        )

        val dataSet = LineDataSet(entries, "").apply {
            color = lineColor
            lineWidth = 1.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            this.fillDrawable = fillDrawable
        }

        binding.chartLevel.data = LineData(dataSet)
        binding.chartLevel.invalidate()
    }

    private fun formatDb(rms: Float): String {
        if (rms <= 1f) return "−∞ dB"
        val db = 20.0 * kotlin.math.log10(rms.toDouble() / 32767.0)
        return "%.0f dB".format(db)
    }

    private fun handleAlert(alert: RoastAlert) {
        val ctx = requireContext()
        when (alert) {
            is RoastAlert.FirstCrackDetected -> {
                animateStripeColor(binding.fcStripe,
                    ContextCompat.getColor(ctx, R.color.lab_amber))
                vibrate(longArrayOf(0, 200, 100, 200))
                val msg = fcDeltaVsReference()
                    ?.let { "First crack — $it" }
                    ?: getString(R.string.alert_fc_detected)
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            }
            is RoastAlert.FirstCrackComplete -> {
                Snackbar.make(binding.root, R.string.alert_fc_complete, Snackbar.LENGTH_SHORT).show()
            }
            is RoastAlert.SecondCrackDetected -> {
                animateStripeColor(binding.scStripe,
                    ContextCompat.getColor(ctx, R.color.lab_red))
                vibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                playAlarm()
                Snackbar.make(binding.root, R.string.alert_sc_detected, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.action_start_cooling) {
                        viewModel.onStartCooling(ctx)
                        findNavController().navigate(R.id.carryoverFragment)
                    }.show()
            }
        }
    }

    private fun animateStripeColor(stripe: View, toColor: Int) {
        val from = (stripe.background as? android.graphics.drawable.ColorDrawable)?.color
            ?: Color.TRANSPARENT
        ValueAnimator.ofObject(ArgbEvaluator(), from, toColor).apply {
            duration = 450
            addUpdateListener { stripe.setBackgroundColor(it.animatedValue as Int) }
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
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        alarmPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(requireContext(), alarmUri)
            isLooping = false
            prepare()
            start()
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
