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
 * Samsung Galaxy S24 Szimultán Sztereó Videófelvevő (Full HD + Hardveres ISP Zoom)
 * 
 * - Hardveres felvétel mindkét fizikai lencséről egyszerre:
 *   Wide (ID: 5, 24mm ekv) -> Bal szem stream
 *   Tele (ID: 6, 67mm ekv) -> Jobb szem stream
 * 
 * - HARDVERES KÉPMÉRET-EGYEZTETÉS (ISP Scaler Crop):
 *   A Főkamera (Wide) video streamjére hardveresen beállítjuk a
 *   CaptureRequest.SCALER_CROP_REGION-t (alapértelmezett 2.79x központi kivágás).
 *   Ezt maga a Samsung Exynos 2400 / Snapdragon 8 Gen 3 ISP processzor végzi el
 *   közvetlenül az 50 MP / 12 MP aktív szenzorméretből, 0% CPU terheléssel!
 *   Ezáltal mind a keresőben, mind a felvett MP4 videón a két felvétel témamérete
 *   pixelre pontosan egyezik, anélkül, hogy utólag lecsökkenne a felbontás!
 * 
 * - Full HD 1080p (1920x1080 @ 30fps) natív hardveres rögzítés:
 *   A MediaRecorder közvetlenül a háttértárba (flash UFS 4.0) streamel kódolt H.264 adatokat,
 *   így a RAM fogyasztás minimális (~20-40 MB), az S24 8GB/12GB memóriája bőségesen elegendő.
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

    // Gyorsvezérlők: zoom finomhangolás és felbontás váltás
    private lateinit var btnZoomMinus: Button
    private lateinit var btnZoomPlus: Button
    private lateinit var tvZoomValue: TextView
    private lateinit var btnToggleResolution: Button

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var recorderWide: MediaRecorder? = null
    private var recorderTele: MediaRecorder? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private var widePhysicalId = "5" // 5.4mm Wide
    private var telePhysicalId = "6" // 7.0mm Tele 3x
    private var currentCropRatio = 2.79f
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

        btnZoomMinus = findViewById(R.id.btnZoomMinus)
        btnZoomPlus = findViewById(R.id.btnZoomPlus)
        tvZoomValue = findViewById(R.id.tvZoomValue)
        btnToggleResolution = findViewById(R.id.btnToggleResolution)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        detectPhysicalCameraIds()

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

        btnZoomMinus.setOnClickListener {
            if (currentCropRatio > 1.20f) {
                currentCropRatio = Math.round((currentCropRatio - 0.05f) * 100f) / 100f
                updateZoomDisplayAndApply()
            }
        }

        btnZoomPlus.setOnClickListener {
            if (currentCropRatio < 4.50f) {
                currentCropRatio = Math.round((currentCropRatio + 0.05f) * 100f) / 100f
                updateZoomDisplayAndApply()
            }
        }

        btnToggleResolution.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "Felvétel közben nem módosítható a felbontás!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isFullHd = !isFullHd
            updateResolutionButtonUI()
            // Újraindítjuk a preview session-t a módosított felbontáshoz
            startStereoPreviewSession()
        }

        updateZoomDisplayUI()
        updateResolutionButtonUI()
    }

    private fun updateZoomDisplayUI() {
        tvZoomValue.text = String.format(Locale.US, "%.2fx", currentCropRatio)
        tvBadgeWide.text = String.format(Locale.US, "BAL: FŐ [%.2fx CROP]", currentCropRatio)
    }

    private fun updateResolutionButtonUI() {
        if (isFullHd) {
            btnToggleResolution.text = "FHD 1080p"
            btnToggleResolution.setBackgroundColor(0xFF059669.toInt()) // zöld
            tvStatus.text = "S24 Full HD Sztereó Videó (1920x1080)"
        } else {
            btnToggleResolution.text = "HD 720p"
            btnToggleResolution.setBackgroundColor(0xFF475569.toInt()) // szürke
            tvStatus.text = "S24 HD Sztereó Videó (1280x720)"
        }
    }

    private fun updateZoomDisplayAndApply() {
        updateZoomDisplayUI()
        applyZoomToActiveSession()
    }

    /**
     * Kiszámítja a főkamera aktív szenzorméretéből a középre igazított SCALER_CROP_REGION téglalapot.
     */
    private fun calculateCropRectForWide(ratio: Float): Rect? {
        return try {
            val pChars = cameraManager.getCameraCharacteristics(widePhysicalId)
            val activeArray = pChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null

            val cropW = (activeArray.width() / ratio).toInt()
            val cropH = (activeArray.height() / ratio).toInt()
            val cropX = activeArray.left + (activeArray.width() - cropW) / 2
            val cropY = activeArray.top + (activeArray.height() - cropH) / 2

            Rect(cropX, cropY, cropX + cropW, cropY + cropH)
        } catch (e: Exception) {
            null
        }
    }

    private fun applyZoomToActiveSession() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val surfaceMain = surfaceViewMain.holder.surface
        val surfaceTele = surfaceViewTele.holder.surface
        if (!surfaceMain.isValid || !surfaceTele.isValid) return

        try {
            val template = if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template).apply {
                addTarget(surfaceMain)
                addTarget(surfaceTele)
                if (isRecording) {
                    recorderWide?.surface?.let { addTarget(it) }
                    recorderTele?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }

            val cropRect = calculateCropRectForWide(currentCropRatio)
            if (cropRect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, cropRect, widePhysicalId)
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
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

    private fun detectPhysicalCameraIds() {
        try {
            val chars0 = cameraManager.getCameraCharacteristics("0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (pId in chars0.physicalCameraIds) {
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        val focalArray: FloatArray? = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focal = if (focalArray != null && focalArray.isNotEmpty()) focalArray[0] else 0f
                        if (focal in 4.5f..6.0f) {
                            widePhysicalId = pId // Főkamera (5.4mm)
                        } else if (focal > 6.0f) {
                            telePhysicalId = pId // Telefotó 3x (7.0mm)
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        } catch (e: Exception) {
            widePhysicalId = "5"
            telePhysicalId = "6"
        }
        tvBadgeWide.text = String.format(Locale.US, "BAL: FŐ [%.2fx CROP]", currentCropRatio)
        tvBadgeTele.text = "JOBB: TELE [$telePhysicalId] (3x NATÍV)"
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
            tvStatus.text = "Kamera előkészítése videóhoz..."
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
                                val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surfaceMain)
                                    addTarget(surfaceTele)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                }

                                // 2.79x ISP CROP beállítása a főkamera streamjére
                                val cropRect = calculateCropRectForWide(currentCropRatio)
                                if (cropRect != null) {
                                    previewRequestBuilder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, cropRect, widePhysicalId)
                                }

                                val previewRequest = previewRequestBuilder.build()
                                session.setRepeatingRequest(previewRequest, null, backgroundHandler)

                                runOnUiThread {
                                    val resLabel = if (isFullHd) "Full HD 1080p" else "HD 720p"
                                    tvStatus.text = "S24 Sztereó Előnézet Kész ($resLabel | Fő: ${String.format(Locale.US, "%.2f", currentCropRatio)}x + Tele: 3x)"
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
        tvStatus.text = "Videó rögzítők előkészítése ($resLabel)..."

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            wideTempFile = File(cacheDir, "TEMP_${timeStamp}_L.mp4")
            teleTempFile = File(cacheDir, "TEMP_${timeStamp}_R.mp4")

            val videoW = if (isFullHd) 1920 else 1280
            val videoH = if (isFullHd) 1080 else 720
            val videoBitrate = if (isFullHd) 18_000_000 else 10_000_000

            // 1. Bal szem felvevő (Főkamera 2.79x ISP Scaler Croppal + Mikrofon sztereó hang)
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

            // 2. Jobb szem felvevő (Telekamera natív 3x, csak videó - mikrofonütközés elkerülésére)
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
                                val recordRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surfaceMain)
                                    addTarget(surfaceTele)
                                    addTarget(recWideSurface)
                                    addTarget(recTeleSurface)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                }

                                // 2.79x CROP beállítása a főkamera streamjére Full HD kimenettel!
                                val cropRect = calculateCropRectForWide(currentCropRatio)
                                if (cropRect != null) {
                                    recordRequestBuilder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, cropRect, widePhysicalId)
                                }

                                val recordRequest = recordRequestBuilder.build()
                                session.setRepeatingRequest(recordRequest, null, backgroundHandler)

                                // Rögzítés egyidejű indítása
                                recorderWide?.start()
                                recorderTele?.start()

                                runOnUiThread {
                                    isRecording = true
                                    btnRecord.isEnabled = true
                                    btnRecord.setImageResource(android.R.drawable.ic_media_pause)
                                    btnToggleResolution.isEnabled = false
                                    tvStatus.text = "SZINKRONIZÁLT SZTEREÓ FELVÉTEL ($resLabel)!"
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
                if (it.exists()) saveVideoToMediaStore(it, filenameL, "S24 Bal Sztereó Videó (${String.format(Locale.US, "%.2f", currentCropRatio)}x Crop, $resLabel)")
            }
            teleTempFile?.let {
                if (it.exists()) saveVideoToMediaStore(it, filenameR, "S24 Jobb Sztereó Videó (3x Natív, $resLabel)")
            }

            runOnUiThread {
                progressBar.visibility = View.GONE
                btnRecord.isEnabled = true
                btnToggleResolution.isEnabled = true
                btnRecord.setImageResource(android.R.drawable.ic_media_play)
                tvTimer.visibility = View.GONE
                tvStatus.text = "Sikeres videómentés ($resLabel) a Movies/Stereo3D mappába ($filenameL & $filenameR)!"
                Toast.makeText(this@StereoVideoActivity, "Full HD sztereó videópár mentve!", Toast.LENGTH_LONG).show()

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
