package com.samsung.s24stereo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
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

    // Samsung S24 hardveresen igazolt fizikai alkamerák a "0" alatt
    private val widePhysicalId = "5" // 5.4mm Wide (Bal szem)
    private val telePhysicalId = "6" // 7.0mm Tele 3x (Jobb szem)
    private val FOCAL_CROP_RATIO = 2.79f

    private var isSurfaceMainReady = false
    private var isSurfaceTeleReady = false

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

        btnShutter.setOnClickListener {
            triggerStereoCapture()
        }

        initSurfaceHolders()
    }

    private fun initSurfaceHolders() {
        surfaceViewMain.holder.setFixedSize(1280, 720)
        surfaceViewMain.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceMainReady = true
                checkSurfacesAndOpen()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceMainReady = false
                closeCameras()
            }
        })

        surfaceViewTele.holder.setFixedSize(1280, 720)
        surfaceViewTele.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceTeleReady = true
                checkSurfacesAndOpen()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceTeleReady = false
                closeCameras()
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

            if (!surfaceMain.isValid || !surfaceTele.isValid) {
                tvStatus.text = "Kijelző felület nem érvényes, újrapróbálkozás..."
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outPreviewMain = OutputConfiguration(surfaceMain).apply { setPhysicalCameraId(widePhysicalId) }
                val outPreviewTele = OutputConfiguration(surfaceTele).apply { setPhysicalCameraId(telePhysicalId) }
                val outStillMain = OutputConfiguration(stillMainSurface).apply { setPhysicalCameraId(widePhysicalId) }
                val outStillTele = OutputConfiguration(stillTeleSurface).apply { setPhysicalCameraId(telePhysicalId) }

                camera.createCaptureSessionByOutputConfigurations(
                    listOf(outPreviewMain, outPreviewTele, outStillMain, outStillTele),
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
                                tvStatus.text = "S24 Sztereó Előnézet Aktív (1x Fő + 3x Tele)"
                                btnShutter.isEnabled = true
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runOnUiThread {
                                tvStatus.text = "A sztereó konfiguráció nem sikerült."
                            }
                        }
                    },
                    backgroundHandler
                )
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
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
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
            backgroundHandler.post {
                try {
                    runOnUiThread {
                        tvStatus.text = "Képillesztés, 2.79x kivágás és 3D Side-by-Side összefűzés..."
                    }

                    // 1. Telefotó feldolgozása (Jobb szem, 67mm natív 3x)
                    val teleBitmap = BitmapFactory.decodeByteArray(teleData, 0, teleData.size)
                        ?: throw IllegalStateException("Telefotó kép dekódolása sikertelen")

                    // 2. Főkamera feldolgozása (2.79x központi kivágás a témaméret egyezéshez)
                    val rawMainBitmap = BitmapFactory.decodeByteArray(mainData, 0, mainData.size)
                        ?: throw IllegalStateException("Főkamera kép dekódolása sikertelen")

                    val croppedMainBitmap = cropToMatchTelephoto(rawMainBitmap, FOCAL_CROP_RATIO, teleBitmap.width, teleBitmap.height)
                    rawMainBitmap.recycle()

                    // 3. Automatikus összefűzés Side-by-Side (SBS) sztereó képre
                    val sbsBitmap = createSideBySideBitmap(croppedMainBitmap, teleBitmap)

                    // 4. Mentés DCIM/Stereo3D könyvtárba
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val filenameSBS = "IMG_${timeStamp}_SBS.jpg"
                    val filenameL = "IMG_${timeStamp}_L.jpg"
                    val filenameR = "IMG_${timeStamp}_R.jpg"

                    saveBitmapToGallery(sbsBitmap, filenameSBS, "S24 Összefűzött Side-by-Side 3D Kép (VR / Sztereó)")
                    saveBitmapToGallery(croppedMainBitmap, filenameL, "S24 Bal Sztereó Kép (Főkamera 2.79x kivágva)")
                    saveBitmapToGallery(teleBitmap, filenameR, "S24 Jobb Sztereó Kép (3x Telefotó natív)")

                    sbsBitmap.recycle()
                    croppedMainBitmap.recycle()
                    teleBitmap.recycle()

                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnShutter.isEnabled = true
                        tvStatus.text = "Sikeres mentés: $filenameSBS a Galériába!"
                        Toast.makeText(this@StereoCaptureActivity, "Összefűzött SBS 3D kép mentve!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnShutter.isEnabled = true
                        tvStatus.text = "Feldolgozási hiba: ${e.message}"
                        Toast.makeText(this@StereoCaptureActivity, "Hiba az összefűzésnél: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    capturedMainBytes = null
                    capturedTeleBytes = null
                }
            }
        }
    }

    private fun createSideBySideBitmap(leftBitmap: Bitmap, rightBitmap: Bitmap): Bitmap {
        val width = leftBitmap.width + rightBitmap.width
        val height = Math.max(leftBitmap.height, rightBitmap.height)
        val composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(composite)
        canvas.drawBitmap(leftBitmap, 0f, 0f, null)
        canvas.drawBitmap(rightBitmap, leftBitmap.width.toFloat(), 0f, null)
        return composite
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
