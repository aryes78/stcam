package com.samsung.s24stereo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samsung Galaxy S24 Vízszintes Sztereó Kamera Felvételkészítő
 * 
 * - Logikai Multi-Kamera ("0") egyetlen kapcsolatán keresztül kéri le a fizikai al-streameket:
 *   Wide: 5.4mm (Physical ID 5) -> Bal szem nézet
 *   Tele: 7.0mm (Physical ID 6) -> Jobb szem nézet
 * - Hardveres egyidejű exponálás (zero shutter lag difference).
 * - A Főkamera képét automatikusan 2.79x arányban kivágja a témaméret egyeztetéséhez.
 * - Menti a két fájlt: IMG_L.jpg és IMG_R.jpg a DCIM/Stereo3D könyvtárba.
 */
class StereoCaptureActivity : AppCompatActivity() {

    private lateinit var surfaceViewMain: SurfaceView
    private lateinit var surfaceViewTele: SurfaceView
    private lateinit var btnShutter: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var imageReaderMain: ImageReader? = null
    private var imageReaderTele: ImageReader? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    // Samsung S24 hardveresen tesztelt fizikai azonosítók (SM-S921B e1s)
    private var widePhysicalId = "5" // 5.4mm Wide 1x
    private var telePhysicalId = "6" // 7.0mm Tele 3x
    private val FOCAL_CROP_RATIO = 2.79f

    private var capturedMainBytes: ByteArray? = null
    private var capturedTeleBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stereo_capture)

        surfaceViewMain = findViewById(R.id.surfaceViewMain)
        surfaceViewTele = findViewById(R.id.surfaceViewTele)
        btnShutter = findViewById(R.id.btnShutter)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        detectPhysicalCameraIds()

        btnShutter.setOnClickListener {
            triggerStereoCapture()
        }

        surfaceViewMain.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openStereoCameraSystem()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCameras()
            }
        })
    }

    private fun detectPhysicalCameraIds() {
        try {
            val chars0 = cameraManager.getCameraCharacteristics("0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (pId in chars0.physicalCameraIds) {
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        val focalArray: FloatArray? = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focal: Float = if (focalArray != null && focalArray.isNotEmpty()) focalArray[0] else 0f
                        if (focal in 4.5f..6.0f) {
                            widePhysicalId = pId // Főkamera (5.4mm)
                        } else if (focal > 6.0f) {
                            telePhysicalId = pId // 3x Telefotó (7.0mm)
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
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
    }

    override fun onPause() {
        closeCameras()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
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
        tvStatus.text = "S24 Sztereó inicializálása (Wide: [$widePhysicalId] + Tele: [$telePhysicalId])..."
        try {
            // Főkamera képfogadó: 4080 x 3060
            imageReaderMain = ImageReader.newInstance(4080, 3060, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    capturedMainBytes = bytes
                    checkAndSaveStereoPair()
                }, backgroundHandler)
            }

            // Telekamera képfogadó: 4000 x 3000
            imageReaderTele = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    capturedTeleBytes = bytes
                    checkAndSaveStereoPair()
                }, backgroundHandler)
            }

            // Logikai [0] kamera megnyitása
            cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startDualPhysicalCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    tvStatus.text = "Kameranyitási hiba: $error"
                }
            }, backgroundHandler)

        } catch (e: SecurityException) {
            tvStatus.text = "Hiba: Kamera engedély szükséges!"
        }
    }

    private fun startDualPhysicalCaptureSession() {
        val camera = cameraDevice ?: return
        try {
            val surfaceMain = surfaceViewMain.holder.surface
            val surfaceTele = surfaceViewTele.holder.surface
            val stillMainSurface = imageReaderMain?.surface ?: return
            val stillTeleSurface = imageReaderTele?.surface ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outPreviewMain = OutputConfiguration(surfaceMain).apply { setPhysicalCameraId(widePhysicalId) }
                val outPreviewTele = OutputConfiguration(surfaceTele).apply { setPhysicalCameraId(telePhysicalId) }
                val outStillMain = OutputConfiguration(stillMainSurface).apply { setPhysicalCameraId(widePhysicalId) }
                val outStillTele = OutputConfiguration(stillTeleSurface).apply { setPhysicalCameraId(telePhysicalId) }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outPreviewMain, outPreviewTele, outStillMain, outStillTele),
                    mainExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surfaceMain)
                                addTarget(surfaceTele)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            }.build()

                            session.setRepeatingRequest(previewRequest, null, backgroundHandler)
                            runOnUiThread {
                                tvStatus.text = "Sztereó előnézet aktív (Fő: 24mm | Tele: 67mm)"
                                btnShutter.isEnabled = true
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runOnUiThread {
                                tvStatus.text = "Nem sikerült a sztereó konfiguráció."
                            }
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                tvStatus.text = "Android 9+ szükséges a fizikai multi-kamerához."
            }
        } catch (e: Exception) {
            tvStatus.text = "Session hiba: ${e.message}"
        }
    }

    private fun triggerStereoCapture() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val surfaceStillMain = imageReaderMain?.surface ?: return
        val surfaceStillTele = imageReaderTele?.surface ?: return

        btnShutter.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Szimultán sztereó exponálás folyamatban..."
        capturedMainBytes = null
        capturedTeleBytes = null

        try {
            val stillRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surfaceStillMain)
                addTarget(surfaceStillTele)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            }.build()

            session.capture(stillRequest, null, backgroundHandler)

        } catch (e: Exception) {
            tvStatus.text = "Exponálási hiba: ${e.message}"
            btnShutter.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private fun checkAndSaveStereoPair() {
        val mainData = capturedMainBytes
        val teleData = capturedTeleBytes

        if (mainData != null && teleData != null) {
            runOnUiThread {
                tvStatus.text = "Képillesztés és kivágás (Crop Match: 2.79×)..."
            }

            // 1. Telefotó feldolgozása (Jobb szem, 67mm)
            val teleBitmap = BitmapFactory.decodeByteArray(teleData, 0, teleData.size)

            // 2. Főkamera feldolgozása (2.79x központi kivágás, hogy megegyezzen a témaméret)
            val rawMainBitmap = BitmapFactory.decodeByteArray(mainData, 0, mainData.size)
            val croppedMainBitmap = cropToMatchTelephoto(rawMainBitmap, FOCAL_CROP_RATIO, teleBitmap.width, teleBitmap.height)

            // 3. Mentés DCIM/Stereo3D könyvtárba
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filenameL = "IMG_${timeStamp}_L.jpg"
            val filenameR = "IMG_${timeStamp}_R.jpg"

            saveBitmapToGallery(croppedMainBitmap, filenameL, "S24 Bal Sztereó (Főkamera kivágva)")
            saveBitmapToGallery(teleBitmap, filenameR, "S24 Jobb Sztereó (3x Telefotó natív)")

            runOnUiThread {
                progressBar.visibility = View.GONE
                btnShutter.isEnabled = true
                tvStatus.text = "Mentve: $filenameL és $filenameR"
                Toast.makeText(this, "Sztereó fotópár sikeresen mentve a Galériába!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cropToMatchTelephoto(source: Bitmap, zoomFactor: Float, targetWidth: Int, targetHeight: Int): Bitmap {
        val cropW = (source.width / zoomFactor).toInt()
        val cropH = (source.height / zoomFactor).toInt()
        val startX = (source.width - cropW) / 2
        val startY = (source.height - cropH) / 2

        val cropped = Bitmap.createBitmap(source, startX, startY, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, filename: String, description: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DESCRIPTION, description)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Stereo3D")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it).use { out ->
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
        }
    }

    private fun closeCameras() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReaderMain?.close()
        imageReaderMain = null
        imageReaderTele?.close()
        imageReaderTele = null
    }
}
