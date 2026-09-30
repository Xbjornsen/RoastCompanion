package com.roastcompanion.ui.tempcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.roastcompanion.databinding.FragmentTempCamBinding
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dev-only capture screen. Records the CBR-101's LED temperature display to an
 * MP4 (video + audio in one synced file) in the app's training dir, so the
 * display can be OCR'd offline (7-segment reader) and the crack sounds on the
 * audio track stay aligned with the temperature readings.
 *
 * Not wired into the roast pipeline — it holds the mic itself, so live crack
 * detection does not run while recording. Reached via Settings → Temp Cam.
 */
class TempCamFragment : Fragment() {

    private var _binding: FragmentTempCamBinding? = null
    private val binding get() = _binding!!

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var audioGranted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cam = grants[Manifest.permission.CAMERA] == true
        audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (cam) {
            startCamera()
        } else {
            binding.tvStatus.text = "Camera permission needed"
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTempCamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRecord.setOnClickListener { toggleRecording() }
        ensurePermissionsThenStart()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        audioGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        if (hasPermission(Manifest.permission.CAMERA) && audioGranted) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            // HD is ample for reading a 7-segment display; keeps files smaller.
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD))
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
                binding.tvStatus.text = if (audioGranted) "Ready" else "Ready (no audio permission)"
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                binding.tvStatus.text = "Camera unavailable"
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun toggleRecording() {
        val capture = videoCapture ?: return
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }

        val startMs = System.currentTimeMillis()
        val dir = File(requireContext().getExternalFilesDir(null), "training").apply { mkdirs() }
        val outFile = File(dir, "tempcam_$startMs.mp4")

        // JSON sidecar so the offline tooling can align by wall-clock start.
        runCatching {
            File(dir, "tempcam_$startMs.json").writeText(
                JSONObject()
                    .put("type", "tempcam")
                    .put("startTimeMs", startMs)
                    .put("video", outFile.name)
                    .toString()
            )
        }

        val opts = FileOutputOptions.Builder(outFile).build()
        var pending = capture.output.prepareRecording(requireContext(), opts)
        if (audioGranted) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(requireContext())) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.btnRecord.text = "Stop recording"
                    binding.tvStatus.text = "● REC 00:00"
                }
                is VideoRecordEvent.Status -> {
                    val secs = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                    binding.tvStatus.text = "● REC %02d:%02d".format(secs / 60, secs % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    binding.btnRecord.text = "Start recording"
                    if (event.hasError()) {
                        Log.e(TAG, "recording error: ${event.error}")
                        binding.tvStatus.text = "Save failed (${event.error})"
                    } else {
                        val mb = outFile.length() / (1024 * 1024)
                        binding.tvStatus.text = "Saved ${outFile.name} (${mb} MB)"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "RC.TempCam"
    }
}
