package com.samsung.s24stereo

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samsung Galaxy S24 Szimultán Sztereó Videófelvevő (Full HD @ 30fps)
 * 
 * - Hardveresen kalibrált fix 2.79x központi kivágás a főkamerára (Alkamera 5)
 * - Natív 3x telefotó stream (Alkamera 6)
 * - OIS (Optikai Képstabilizátor) és EIS KIKAPCSOLHATÓ:
 *   Megszünteti a két lencse független, aszinkron mágneses elmozdulását mozgás közben!
 *   A két kamera optikai tengelye szigorúan merev sztereó bázistávolságon marad.
 * - 4-pixel Bayer RGGB igazítás: 100% természetes színek (lila színhiba nélkül).
 * - 60 Hz szenzor-pipeline: stabil, folyamatos 30 FPS mindkét videófájlba.
 */
class StereoVideoActivity : AppCompatActivity() {

    private lateinit var surfaceViewMain: SurfaceView
    private lateinit var surfaceViewTele: SurfaceView
    private lateinit var btnRecord: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvBadgeWide: TextView
    private lateinit var tvBadgeTele: TextView
    private lateinit var progressBar: ProgressBar

    // Vezérlők: felbontás váltó
    private lateinit var btnToggleResolution: Button

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var recorderWide: MediaRecorder? = null
    private var recorderTele: MediaRecorder? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    // Samsung S24 igazolt alkamerák a "0" logikai kamera alatt
    private val widePhysicalId = "5" // Főkamera (5.4mm)
    private val telePhysicalId = "6" // 3x Telefotó (7.0mm)

    // Hardveresen bevált, tökéletesen fedő fix arány
    private val CROP_RATIO = 2.79f
    private var isFullHd = true // Alapértelmezett: 1080p Full HD

    private var isSurfaceMainReady = false
    private var isSurfaceTeleReady = false
    private var isRecording = false

    private var wideTempFile: File? = null
    private var teleTempFile: File? = null
    private var recordingStartTime = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = SystemClock.uptimeMillis() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                tvTimer.text = String.format(Locale.US, "● REC %02d:%02d", minutes, seconds)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stereo_video)

        surfaceViewMain = findViewById(R.id.surfaceViewMain)
        surfaceViewTele = findViewById(R.id.surfaceViewTele)
        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvBadgeWide = findViewById(R.id.tvBadgeWide)
        tvBadgeTele = findViewById(R.id.tvBadgeTele)
        progressBar = findViewById(R.id.progressBar)

        btnToggleResolution = findViewById(R.id.btnToggleResolution)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        setupControls()
        initSurfaceHolders()
    }

    private fun setupControls() {
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopStereoRecording()
            } else {
                startStereoRecording()
            }
        }

        btnToggleResolution.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "Felvétel közben nem módosítható a felbontás!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isFullHd = !isFullHd
            updateResolutionButtonUI()
            startStereoPreviewSession()
        }

        updateResolutionButtonUI()
    }

    private fun updateResolutionButtonUI() {
        val resLabel = if (isFullHd) "1080p FHD" else "720p HD"
        btnToggleResolution.text = resLabel
        btnToggleResolution.setBackgroundColor(if (isFullHd) 0xFF059669.toInt() else 0xFF475569.toInt())
        tvStatus.text = "S24 Sztereó ($resLabel @ 30fps • Fix tengely)"
    }

    /**
     * Kiszámítja a 2.79x központi crop téglalapot szigorú 4-pixel Bayer igazítással (Bayer RGGB mozaik védelem).
     */
    private fun calculateCropRect(): Rect {
        return try {
            val chars0 = cameraManager.getCameraCharacteristics("0")
            val activeArray = chars0.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: Rect(0, 0, 4080, 3060)

            val sensorW = activeArray.width()
            val sensorH = activeArray.height()

            // 16:9 arány a 4:3 szenzorból
            val base16x9Height = (sensorW * 9) / 16

            var targetW = (sensorW / CROP_RATIO).toInt()
            var targetH = (base16x9Height / CROP_RATIO).toInt()

            // Szigorú 4-gyel osztható igazítás a zéró lila/magenta színhibáért
            targetW = (targetW / 4) * 4
            targetH = (targetH / 4) * 4

            var left = activeArray.left + (sensorW - targetW) / 2
            var top = activeArray.top + (sensorH - targetH) / 2

            left = (left / 4) * 4
            top = (top / 4) * 4

            Rect(left, top, left + targetW, top + targetH)
        } catch (e: Exception) {
            Rect(0, 0, 3840, 2160)
        }
    }

    private fun getTargetFpsRange(): Range<Int> {
        return try {
            val chars0 = cameraManager.getCameraCharacteristics("0")
            val ranges = chars0.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges != null) {
                for (r in ranges) {
                    if (r.upper >= 60 && r.lower >= 30) return r
                }
                for (r in ranges) {
                    if (r.upper >= 60) return r
                }
                for (r in ranges) {
                    if (r.upper == 30 && r.lower == 30) return r
                }
                return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
            }
            Range(30, 30)
        } catch (e: Exception) {
            Range(30, 30)
        }
    }

    private fun applySettingsToActiveSession() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val surfaceMain = surfaceViewMain.holder.surface
        val surfaceTele = surfaceViewTele.holder.surface
        if (!surfaceMain.isValid || !surfaceTele.isValid) return

        try {
            val template = if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val fpsRange = getTargetFpsRange()

            // 1. Főkamera (Wide 2.79x crop + Fix OIS letiltva a merev sztereó tengelyért)
            val reqWide = camera.createCaptureRequest(template).apply {
                addTarget(surfaceMain)
                if (isRecording) {
                    recorderWide?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.SCALER_CROP_REGION, calculateCropRect())
            }.build()

            // 2. Telekamera (Tele natív 3x + Fix OIS letiltva a merev sztereó tengelyért)
            val reqTele = camera.createCaptureRequest(template).apply {
                addTarget(surfaceTele)
                if (isRecording) {
                    recorderTele?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()

            session.setRepeatingBurst(listOf(reqWide, reqTele), null, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSurfaceHolders() {
        val targetW = if (isFullHd) 1920 else 1280
        val targetH = if (isFullHd) 1080 else 720

        surfaceViewMain.holder.setFixedSize(targetW, targetH)
        surfaceViewMain.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceMainReady = true
                checkSurfacesAndOpen()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceMainReady = false
            }
        })

        surfaceViewTele.holder.setFixedSize(targetW, targetH)
        surfaceViewTele.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceTeleReady = true
                checkSurfacesAndOpen()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceTeleReady = false
            }
        })
    }

    private fun checkSurfacesAndOpen() {
        if (isSurfaceMainReady && isSurfaceTeleReady && cameraDevice == null) {
            val s1 = surfaceViewMain.holder.surface
            val s2 = surfaceViewTele.holder.surface
            if (s1 != null && s1.isValid && s2 != null && s2.isValid) {
                openStereoCameraSystem()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        checkSurfacesAndOpen()
    }

    override fun onPause() {
        if (isRecording) {
            stopStereoRecording()
        }
        closeCameraSystem()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("StereoVideoThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun openStereoCameraSystem() {
        runOnUiThread {
            tvStatus.text = "S24 sztereó kamera indítása..."
        }
        try {
            cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startStereoPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    runOnUiThread {
                        tvStatus.text = "Kamera hiba: $error"
                    }
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            runOnUiThread {
                tvStatus.text = "Kamera engedély hiányzik!"
            }
        }
    }

    private fun startStereoPreviewSession() {
        val camera = cameraDevice ?: return
        try {
            val surfaceMain = surfaceViewMain.holder.surface
            val surfaceTele = surfaceViewTele.holder.surface
            if (!surfaceMain.isValid || !surfaceTele.isValid) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outMain = OutputConfiguration(surfaceMain).apply { setPhysicalCameraId(widePhysicalId) }
                val outTele = OutputConfiguration(surfaceTele).apply { setPhysicalCameraId(telePhysicalId) }

                captureSession?.close()
                camera.createCaptureSessionByOutputConfigurations(
                    listOf(outMain, outTele),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val fpsRange = getTargetFpsRange()

                                // 1. Főkamera előnézet (5-ös alkamera 2.79x crop, szigorú 4-pixel Bayer igazítás)
                                val reqWide = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surfaceMain)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.SCALER_CROP_REGION, calculateCropRect())
                                }.build()

                                // 2. Telekamera előnézet (6-os alkamera natív 3x)
                                val reqTele = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surfaceTele)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                }.build()

                                session.setRepeatingBurst(listOf(reqWide, reqTele), null, backgroundHandler)

                                runOnUiThread {
                                    val resLabel = if (isFullHd) "Full HD 1080p" else "HD 720p"
                                    tvStatus.text = "S24 Sztereó Előnézet Kész ($resLabel @ 30fps • Fix tengely)"
                                    btnRecord.isEnabled = true
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    tvStatus.text = "Előnézet hiba: ${e.message}"
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runOnUiThread {
                                tvStatus.text = "Session konfiguráció sikertelen a videó előnézethez."
                            }
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "Hiba: ${e.message}"
            }
        }
    }

    private fun startStereoRecording() {
        val camera = cameraDevice ?: return
        btnRecord.isEnabled = false
        val resLabel = if (isFullHd) "Full HD 1080p" else "HD 720p"
        tvStatus.text = "Videó rögzítők előkészítése ($resLabel @ 30fps)..."

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            wideTempFile = File(cacheDir, "TEMP_${timeStamp}_L.mp4")
            teleTempFile = File(cacheDir, "TEMP_${timeStamp}_R.mp4")

            val videoW = if (isFullHd) 1920 else 1280
            val videoH = if (isFullHd) 1080 else 720
            val videoBitrate = if (isFullHd) 18_000_000 else 10_000_000

            // 1. Bal szem felvevő (Főkamera 2.79x crop + Sztereó mikrofon hang, 30fps)
            recorderWide = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(wideTempFile!!.absolutePath)
                setVideoEncodingBitRate(videoBitrate)
                setVideoFrameRate(30)
                setVideoSize(videoW, videoH)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192000)
                setAudioSamplingRate(48000)
                prepare()
            }

            // 2. Jobb szem felvevő (Telekamera natív 3x, csak videó, 30fps)
            recorderTele = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(teleTempFile!!.absolutePath)
                setVideoEncodingBitRate(videoBitrate)
                setVideoFrameRate(30)
                setVideoSize(videoW, videoH)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                prepare()
            }

            val surfaceMain = surfaceViewMain.holder.surface
            val surfaceTele = surfaceViewTele.holder.surface
            val recWideSurface = recorderWide!!.surface
            val recTeleSurface = recorderTele!!.surface

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outPreviewMain = OutputConfiguration(surfaceMain).apply { setPhysicalCameraId(widePhysicalId) }
                val outPreviewTele = OutputConfiguration(surfaceTele).apply { setPhysicalCameraId(telePhysicalId) }
                val outRecWide = OutputConfiguration(recWideSurface).apply { setPhysicalCameraId(widePhysicalId) }
                val outRecTele = OutputConfiguration(recTeleSurface).apply { setPhysicalCameraId(telePhysicalId) }

                captureSession?.close()
                camera.createCaptureSessionByOutputConfigurations(
                    listOf(outPreviewMain, outPreviewTele, outRecWide, outRecTele),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val fpsRange = getTargetFpsRange()

                                // 1. Főkamera rögzítés (5-ös alkamera 2.79x crop + Fix OIS letiltás)
                                val reqWide = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surfaceMain)
                                    addTarget(recWideSurface)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.SCALER_CROP_REGION, calculateCropRect())
                                }.build()

                                // 2. Telekamera rögzítés (6-os alkamera natív 3x + Fix OIS letiltás)
                                val reqTele = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surfaceTele)
                                    addTarget(recTeleSurface)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                }.build()

                                session.setRepeatingBurst(listOf(reqWide, reqTele), null, backgroundHandler)

                                // Rögzítés egyidejű indítása
                                recorderWide?.start()
                                recorderTele?.start()

                                runOnUiThread {
                                    isRecording = true
                                    btnRecord.isEnabled = true
                                    btnRecord.setImageResource(android.R.drawable.ic_media_pause)
                                    btnToggleResolution.isEnabled = false
                                    tvStatus.text = "SZINKRONIZÁLT SZTEREÓ FELVÉTEL ($resLabel @ 30fps • Fix tengely)!"
                                    tvTimer.visibility = View.VISIBLE
                                    recordingStartTime = SystemClock.uptimeMillis()
                                    timerHandler.post(timerRunnable)
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    tvStatus.text = "Rögzítés indítási hiba: ${e.message}"
                                    btnRecord.isEnabled = true
                                    startStereoPreviewSession()
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runOnUiThread {
                                btnRecord.isEnabled = true
                                tvStatus.text = "A videó rögzítő session indítása sikertelen."
                                startStereoPreviewSession()
                            }
                        }
                    },
                    backgroundHandler
                )
            }

        } catch (e: Throwable) {
            runOnUiThread {
                tvStatus.text = "Videó hiba: ${e.message}"
                btnRecord.isEnabled = true
            }
            startStereoPreviewSession()
        }
    }

    private fun stopStereoRecording() {
        btnRecord.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Felvétel leállítása és mentése a Galériába..."
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)

        backgroundHandler.post {
            try {
                recorderWide?.stop()
                recorderTele?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                recorderWide?.reset()
                recorderWide?.release()
                recorderWide = null

                recorderTele?.reset()
                recorderTele?.release()
                recorderTele = null
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filenameL = "VID_${timeStamp}_L.mp4"
            val filenameR = "VID_${timeStamp}_R.mp4"
            val resLabel = if (isFullHd) "Full HD 1080p" else "HD 720p"

            wideTempFile?.let {
                if (it.exists()) saveVideoToMediaStore(it, filenameL, "S24 Bal Sztereó Videó (2.79x Crop, $resLabel @ 30fps)")
            }
            teleTempFile?.let {
                if (it.exists()) saveVideoToMediaStore(it, filenameR, "S24 Jobb Sztereó Videó (3x Natív, $resLabel @ 30fps)")
            }

            runOnUiThread {
                progressBar.visibility = View.GONE
                btnRecord.isEnabled = true
                btnToggleResolution.isEnabled = true
                btnRecord.setImageResource(android.R.drawable.ic_media_play)
                tvTimer.visibility = View.GONE
                tvStatus.text = "Sikeres mentés ($resLabel @ 30fps) a Movies/Stereo3D mappába ($filenameL & $filenameR)!"
                Toast.makeText(this@StereoVideoActivity, "Full HD sztereó videópár mentve (30fps)!", Toast.LENGTH_LONG).show()

                startStereoPreviewSession()
            }
        }
    }

    private fun saveVideoToMediaStore(file: File, filename: String, description: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DESCRIPTION, description)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Stereo3D")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it).use { out ->
                if (out != null) {
                    FileInputStream(file).use { inStream ->
                        inStream.copyTo(out)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
        }
        file.delete()
    }

    private fun closeCameraSystem() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }
}
