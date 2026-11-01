package com.samsung.s24stereo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samsung Galaxy S24 Vízszintes Sztereó Kamera Felvételkészítő
 * 
 * - Vízszintes tájolásban használatos (sztereó bázistávolság: ~18.5 mm).
 * - Egyetlen exponálógomb megnyomására elkészíti a felvételt a 3x Tele és az 1x Főkamerával.
 * - A Főkamera képét automatikusan 2.79x - 3.0x arányban kivágja a képméret-egyeztetéshez.
 * - Menti a két fájlt: IMG_L.jpg és IMG_R.jpg a DCIM/Stereo3D könyvtárba.
 */
class StereoCaptureActivity : AppCompatActivity() {

    private lateinit var surfaceViewMain: SurfaceView
    private lateinit var surfaceViewTele: SurfaceView
    private lateinit var btnShutter: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var cameraManager: CameraManager
    private var cameraMain: CameraDevice? = null
    private var cameraTele: CameraDevice? = null

    private var imageReaderMain: ImageReader? = null
    private var imageReaderTele: ImageReader? = null

    private var sessionMain: CameraCaptureSession? = null
    private var sessionTele: CameraCaptureSession? = null

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    // Samsung S24 optikai arány: 67mm (Tele) / 24mm (Wide) ~ 2.7916x
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

        btnShutter.setOnClickListener {
            triggerStereoCapture()
        }

        surfaceViewMain.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openCameras()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCameras()
            }
        })
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

    private fun openCameras() {
        try {
            // Főkamera (Wide) előkészítése: ID "0"
            imageReaderMain = ImageReader.newInstance(4080, 3060, ImageFormat.JPEG, 2)
            imageReaderMain?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                capturedMainBytes = bytes
                checkAndSaveStereoPair()
            }, backgroundHandler)

            cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraMain = camera
                    startPreviewMain()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)

            // Telekamera (3x) előkészítése: ID "2" (vagy fallback "52")
            imageReaderTele = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 2)
            imageReaderTele?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                capturedTeleBytes = bytes
                checkAndSaveStereoPair()
            }, backgroundHandler)

            cameraManager.openCamera("2", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraTele = camera
                    startPreviewTele()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)

        } catch (e: SecurityException) {
            tvStatus.text = "Hiba: Kamera jogosultság hiányzik!"
        }
    }

    private fun startPreviewMain() {
        val surface = surfaceViewMain.holder.surface
        val builder = cameraMain?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }
        cameraMain?.createCaptureSession(listOf(surface, imageReaderMain?.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessionMain = session
                builder?.build()?.let { session.setRepeatingRequest(it, null, backgroundHandler) }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun startPreviewTele() {
        val surface = surfaceViewTele.holder.surface
        val builder = cameraTele?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }
        cameraTele?.createCaptureSession(listOf(surface, imageReaderTele?.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessionTele = session
                builder?.build()?.let { session.setRepeatingRequest(it, null, backgroundHandler) }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun triggerStereoCapture() {
        btnShutter.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Exponálás mindkét kamerával..."
        capturedMainBytes = null
        capturedTeleBytes = null

        try {
            // Szimultán exponálás indítása mindkét kamerán
            val captureReqMain = cameraMain?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                imageReaderMain?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            }
            val captureReqTele = cameraTele?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                imageReaderTele?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            }

            captureReqMain?.build()?.let { sessionMain?.capture(it, null, backgroundHandler) }
            captureReqTele?.build()?.let { sessionTele?.capture(it, null, backgroundHandler) }

        } catch (e: Exception) {
            tvStatus.text = "Hiba a felvételkor: ${e.message}"
            btnShutter.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private fun checkAndSaveStereoPair() {
        val mainData = capturedMainBytes
        val teleData = capturedTeleBytes

        if (mainData != null && teleData != null) {
            runOnUiThread {
                tvStatus.text = "Képillesztés és kivágás (Crop Match)..."
            }

            // 1. Telefotó feldolgozása (Jobb szem nézet, teljes felbontás)
            val teleBitmap = BitmapFactory.decodeByteArray(teleData, 0, teleData.size)

            // 2. Főkamera feldolgozása: 2.79x központi kivágás, hogy a témaméret egyezzen a teleképével
            val rawMainBitmap = BitmapFactory.decodeByteArray(mainData, 0, mainData.size)
            val croppedMainBitmap = cropToMatchTelephoto(rawMainBitmap, FOCAL_CROP_RATIO, teleBitmap.width, teleBitmap.height)

            // 3. Fájlnevek generálása: IMG_YYYYMMDD_HHMMSS_L.jpg és _R.jpg
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filenameL = "IMG_${timeStamp}_L.jpg"
            val filenameR = "IMG_${timeStamp}_R.jpg"

            saveBitmapToGallery(croppedMainBitmap, filenameL, "S24 Bal Sztereó Kép (Főkamera kivágva)")
            saveBitmapToGallery(teleBitmap, filenameR, "S24 Jobb Sztereó Kép (3x Telefotó natív)")

            runOnUiThread {
                progressBar.visibility = View.GONE
                btnShutter.isEnabled = true
                tvStatus.text = "Sikeres sztereó mentés:\n$filenameL és $filenameR"
                Toast.makeText(this, "Sztereó pár mentve a Galériába!", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Kivágja a főkamera képének közepét a megadott nagyítási aránynak megfelelően,
     * majd a telekamera felbontására skálázza a tökéletes sztereó párhoz.
     */
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
        sessionMain?.close()
        sessionMain = null
        sessionTele?.close()
        sessionTele = null
        cameraMain?.close()
        cameraMain = null
        cameraTele?.close()
        cameraTele = null
        imageReaderMain?.close()
        imageReaderMain = null
        imageReaderTele?.close()
        imageReaderTele = null
    }
}