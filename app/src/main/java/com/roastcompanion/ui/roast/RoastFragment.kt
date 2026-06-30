package com.roastcompanion.ui.roast

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.LinearLayout
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
import android.view.animation.LinearInterpolator
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

    companion object {
        // Fallback pacing for the card flood when no reference roast is set.
        private const val NOMINAL_FC_TO_SC_MS = 150_000f   // ~2.5 min FC→SC
        private const val NOMINAL_SC_TAIL_MS  = 90_000f    // ~1.5 min SC→pull
    }

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
        binding.btnStart.setOnClickListener {
            requestPermissionsAndStart()
        }

        binding.btnStop.setOnClickListener {
            viewModel.onStopRoast(requireContext())
        }

        binding.btnPauseResume.setOnClickListener {
            if (viewModel.isPaused.value) {
                viewModel.onResumeRoast()
            } else {
                viewModel.onPauseRoast()
            }
        }

        binding.btnReset.setOnClickListener {
            val msg = if (viewModel.isSessionActive())
                "This will discard the current roast and all detected crack events."
            else
                "Clear the timer and start fresh."
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset?")
                .setMessage(msg)
                .setPositiveButton("Reset") { _, _ ->
                    rmsHistory.clear()
                    binding.chartLevel.clear()
                    binding.chartLevel.invalidate()
                    viewModel.onResetRoast(requireContext())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnScDismiss.setOnClickListener { hideScAlert() }
        binding.btnScCool.setOnClickListener {
            hideScAlert()
            startCoolingFlow()
        }

        binding.chipConfirmFcStart.setOnClickListener { viewModel.confirmCrack("FC_START") }
        binding.chipConfirmFcEnd.setOnClickListener   { viewModel.confirmCrack("FC_END") }
        binding.chipConfirmSc.setOnClickListener      { viewModel.confirmCrack("SC_START") }

        // Keep the scroll content clear of the pinned action footer, whatever
        // its current height (it changes as Start ↔ Pause/Stop/Reset swap in).
        val footerGap = (8 * resources.displayMetrics.density).toInt()
        binding.actionFooter.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val needed = (bottom - top) + footerGap
            val sv = binding.scrollRoot
            if (sv.paddingBottom != needed) {
                sv.setPadding(sv.paddingLeft, sv.paddingTop, sv.paddingRight, needed)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.sessionTimerMs.collect { ms ->
                        binding.tvTimer.text = TimeFormatter.formatElapsed(ms)
                        updateProgressBars()
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
                    viewModel.fcStartElapsedMs.collect { ms ->
                        binding.tvFcStart.text = ms?.let { TimeFormatter.formatElapsed(it) }
                            ?: getString(R.string.not_detected)
                        updateProgressBars()
                    }
                }

                launch {
                    viewModel.fcEndElapsedMs.collect { ms ->
                        binding.tvFcEnd.text = ms?.let { TimeFormatter.formatElapsed(it) }
                            ?: getString(R.string.not_detected)
                    }
                }

                launch {
                    combine(viewModel.fcStartElapsedMs, viewModel.fcEndElapsedMs) { start, end ->
                        if (start != null && end != null) TimeFormatter.formatDuration(end - start)
                        else getString(R.string.not_detected)
                    }.collect { text ->
                        binding.tvFcDuration.text = text
                    }
                }

                launch {
                    viewModel.scElapsedMs.collect { ms ->
                        binding.tvScDetected.text = ms?.let { TimeFormatter.formatElapsed(it) }
                            ?: getString(R.string.not_detected)
                        updateProgressBars()
                    }
                }

                launch {
                    viewModel.alerts.collect { alert ->
                        handleAlert(alert)
                    }
                }

                launch {
                    combine(viewModel.phase, viewModel.isPaused) { phase, paused ->
                        phase to paused
                    }.collect { (phase, paused) ->
                        updateButtonVisibility(phase, paused)
                    }
                }

                launch {
                    combine(viewModel.phase, viewModel.sessionTimerMs) { phase, ms ->
                        phase != RoastPhase.IDLE || ms > 0L
                    }.collect { showReset ->
                        binding.btnReset.visibility = if (showReset) View.VISIBLE else View.GONE
                        fixControlRowGaps()
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

                launch {
                    combine(viewModel.crackCount, viewModel.phase) { count, phase ->
                        count to phase
                    }.collect { (count, phase) ->
                        binding.tvCrackCount.visibility =
                            if (phase != RoastPhase.IDLE) View.VISIBLE else View.GONE
                        binding.tvCrackCount.text = if (count == 1) "1 crack" else "$count cracks"
                    }
                }

                launch {
                    combine(viewModel.confirmedTypes, viewModel.phase) { confirmed, phase ->
                        confirmed to phase
                    }.collect { (confirmed, phase) ->
                        val active = phase != RoastPhase.IDLE
                        binding.rowConfirm.visibility = if (active) View.VISIBLE else View.GONE
                        val amber = ContextCompat.getColor(requireContext(), R.color.lab_amber)
                        val dim   = ContextCompat.getColor(requireContext(), R.color.lab_text_dim)
                        binding.chipConfirmFcStart.setTextColor(if ("FC_START" in confirmed) amber else dim)
                        binding.chipConfirmFcEnd.setTextColor(  if ("FC_END"   in confirmed) amber else dim)
                        binding.chipConfirmSc.setTextColor(     if ("SC_START" in confirmed) amber else dim)
                    }
                }

                launch {
                    combine(viewModel.recordForTraining, viewModel.phase) { rec, phase ->
                        rec && phase != RoastPhase.IDLE
                    }.collect { showRec ->
                        binding.tvRecIndicator.visibility = if (showRec) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    combine(
                        viewModel.rmsLevel,
                        viewModel.ambientLevel,
                        viewModel.diagAmpRatio,
                        viewModel.diagSpecRatio,
                        viewModel.phase
                    ) { rms: Float, amb: Float, ampEvt: Float, spec: Float, phase: RoastPhase ->
                        when {
                            phase == RoastPhase.IDLE -> null
                            amb < 10f -> "calibrating…"
                            else -> {
                                val liveRatio = if (amb > 0f) rms / amb else 0f
                                val ratioStr = "×${"%.1f".format(liveRatio)}"
                                if (ampEvt == 0f) ratioStr
                                else "$ratioStr  spec ${"%.2f".format(spec)}"
                            }
                        }
                    }.collect { text ->
                        if (text == null) {
                            binding.tvDiag.visibility = View.GONE
                        } else {
                            binding.tvDiag.visibility = View.VISIBLE
                            binding.tvDiag.text = text
                        }
                    }
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

    /**
     * Floods the FC / SC cards as the roast progresses.
     *  - FC card: empty until first crack fires, then fills across the FC→SC
     *    stretch, locking full when second crack fires.
     *  - SC card: empty until second crack fires, then fills toward the usual
     *    pull/cooling point — you generally pull before it tops out.
     * The reference roast (favourite) supplies the pacing; without one, the
     * nominal gaps below are used so the fill still moves at a sane rate.
     */
    private fun updateProgressBars() {
        val ref = viewModel.referenceRoast.value
        val now = viewModel.sessionTimerMs.value
        val refFc   = ref?.firstCrackStartMs?.let { it - ref.startTimeMs }?.takeIf { it > 0 }
        val refSc   = ref?.secondCrackDetectedMs?.let { it - ref.startTimeMs }?.takeIf { it > 0 }
        val refCool = ref?.coolingStartedMs?.let { it - ref.startTimeMs }?.takeIf { it > 0 }
        val fcAt = viewModel.fcStartElapsedMs.value
        val scAt = viewModel.scElapsedMs.value

        val fcFrac = when {
            scAt != null -> 1f
            fcAt != null -> {
                val gap = if (refFc != null && refSc != null && refSc > refFc) (refSc - refFc).toFloat()
                          else NOMINAL_FC_TO_SC_MS
                ((now - fcAt).toFloat() / gap).coerceIn(0f, 1f)
            }
            else -> 0f
        }
        val scFrac = when {
            scAt != null -> {
                val tail = if (refSc != null && refCool != null && refCool > refSc) (refCool - refSc).toFloat()
                           else NOMINAL_SC_TAIL_MS
                ((now - scAt).toFloat() / tail).coerceIn(0f, 1f)
            }
            else -> 0f
        }
        setCardFill(binding.cardFirstCrack, fcFrac)
        setCardFill(binding.cardSecondCrack, scFrac)
        updateRoastLevel(now, fcAt, scAt)
    }

    /**
     * Live "if you dropped now" roast level, by development time since first
     * crack (and into second crack). Rough guide for a CBR-101 — tune the
     * thresholds to taste.
     */
    private fun updateRoastLevel(now: Long, fcAt: Long?, scAt: Long?) {
        val (label, dark) = when {
            scAt != null ->
                if (now - scAt < 30_000) "Vienna · dark" to true
                else "French · very dark" to true
            fcAt != null -> when (now - fcAt) {
                in 0 until 75_000      -> "City · light" to false
                in 75_000 until 135_000 -> "City+ · light-medium" to false
                in 135_000 until 180_000 -> "Full City · medium" to false
                else                    -> "Full City+ · medium-dark" to true
            }
            else -> null to false
        }
        if (label == null) {
            binding.tvRoastLevel.visibility = View.GONE
        } else {
            binding.tvRoastLevel.visibility = View.VISIBLE
            binding.tvRoastLevel.text = label
            binding.tvRoastLevel.setTextColor(
                ContextCompat.getColor(requireContext(),
                    if (dark) R.color.lab_red else R.color.lab_amber)
            )
        }
    }

    private val fillAnimators = HashMap<Int, ValueAnimator>()

    private fun setCardFill(card: View, fraction: Float) {
        val clip = (card.background as? LayerDrawable)?.getDrawable(1) ?: return
        val target = (fraction * 10000).toInt().coerceIn(0, 10000)
        if (clip.level == target) return
        fillAnimators[card.id]?.cancel()
        // Snap large jumps (reset, lock-to-full); smoothly animate the gradual
        // creep between the 500 ms timer ticks so the fill doesn't step.
        if (kotlin.math.abs(target - clip.level) > 4000) {
            clip.level = target
            return
        }
        fillAnimators[card.id] = ValueAnimator.ofInt(clip.level, target).apply {
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { clip.level = it.animatedValue as Int }
            start()
        }
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

        // Dismiss the SC alert sheet (and its alarm) on any move away from SC.
        if (phase != RoastPhase.SECOND_CRACK_ACTIVE &&
            binding.scAlertSheet.visibility == View.VISIBLE) {
            hideScAlert()
        }
    }

    private fun updateButtonVisibility(phase: RoastPhase, paused: Boolean) {
        val isActive = phase != RoastPhase.IDLE
        binding.btnStart.visibility = if (isActive) View.GONE else View.VISIBLE
        binding.btnPauseResume.visibility = if (isActive) View.VISIBLE else View.GONE
        binding.btnStop.visibility = if (isActive) View.VISIBLE else View.GONE
        binding.btnPauseResume.text = if (paused) "Resume" else "Pause"
        fixControlRowGaps()
    }

    /** Even 8dp gaps between whichever control-row buttons are currently visible. */
    private fun fixControlRowGaps() {
        val row = binding.controlRow
        val gap = (8 * resources.displayMetrics.density).toInt()
        val visible = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
        visible.forEachIndexed { i, v ->
            (v.layoutParams as LinearLayout.LayoutParams).apply {
                marginEnd = if (i == visible.lastIndex) 0 else gap
                v.layoutParams = this
            }
        }
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
                showScAlert()
            }
        }
    }

    private fun showScAlert() {
        binding.scAlertSheet.visibility = View.VISIBLE
    }

    private fun hideScAlert() {
        stopAlarm()
        binding.scAlertSheet.visibility = View.GONE
    }

    private fun startCoolingFlow() {
        stopAlarm()
        binding.scAlertSheet.visibility = View.GONE
        viewModel.onStartCooling(requireContext())
        findNavController().navigate(R.id.carryoverFragment)
    }

    private fun stopAlarm() {
        alarmPlayer?.let { if (it.isPlaying) it.stop() }
        alarmPlayer?.release()
        alarmPlayer = null
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
        rmsHistory.clear()
        binding.chartLevel.clear()
        binding.chartLevel.invalidate()
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
        fillAnimators.values.forEach { it.cancel() }
        fillAnimators.clear()
        alarmPlayer?.release()
        alarmPlayer = null
        _binding = null
    }
}
