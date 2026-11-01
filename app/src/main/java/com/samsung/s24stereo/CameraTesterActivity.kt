package com.samsung.s24stereo

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Samsung Galaxy S24 Kamera Diagnosztikai és Felderítő Tesztalkalmazás
 * 
 * 1. Lekérdezi az összes elérhető logikai és fizikai kamerát.
 * 2. Felderíti a [0]-s logikai kamera fizikai alegységeit (S24-en: 2 = UltraWide 2.2mm, 5 = Wide 5.4mm, 6 = Tele 7.0mm).
 * 3. Teszteli a 'B' Módszert: Logikai Multi-Camera egyetlen CameraDevice alatti fizikai streamelés (OutputConfiguration.setPhysicalCameraId).
 */
class CameraTesterActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var btnStartTest: Button
    private lateinit var cameraManager: CameraManager
    private val backgroundHandler = Handler(Looper.getMainLooper())

    private var widePhysicalId = "5"
    private var telePhysicalId = "6"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_test)

        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()
        btnStartTest = findViewById(R.id.btnStartTest)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        btnStartTest.setOnClickListener {
            runFullDiagnostic()
        }

        // Automatikus indítás belépéskor
        runFullDiagnostic()
    }

    private fun log(message: String) {
        tvLog.append(message + "\n")
    }

    private fun runFullDiagnostic() {
        tvLog.text = ""
        log("=========================================")
        log("SAMSUNG S24 KAMERA HARDVER DIAGNOSZTIKA")
        log("Eszköz: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        log("Android verzió: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("=========================================\n")

        try {
            val cameraIds = cameraManager.cameraIdList
            log("1. ELÉRHETŐ LOGIKAI KAMERÁK: ${cameraIds.size} db")
            
            val chars0 = cameraManager.getCameraCharacteristics("0")
            val capabilities = chars0.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val isLogicalMulti = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            
            log("-> Kamera [0]: HÁTSÓ FŐKAMERA | Multi-kamera: ${if (isLogicalMulti) "IGEN" else "NEM"}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLogicalMulti) {
                val physicalIds = chars0.physicalCameraIds
                log("\n2. FIZIKAI ALEGYSÉGEK (Physical Sub-Cameras): ${physicalIds.joinToString(", ")}")
                
                for (pId in physicalIds) {
                    try {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        val focalArray: FloatArray? = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focal: Float = if (focalArray != null && focalArray.isNotEmpty()) focalArray[0] else 0f
                        val role = when {
                            focal < 3.0f -> "0.6× Ultraszéles (13mm ekv)"
                            focal in 4.5f..6.0f -> {
                                widePhysicalId = pId
                                "1× Főkamera (24mm ekv) -> [SZTEREÓ BAL SZEM]"
                            }
                            focal > 6.0f -> {
                                telePhysicalId = pId
                                "3× Telefotó (67mm ekv) -> [SZTEREÓ JOBB SZEM]"
                            }
                            else -> "Egyéb"
                        }
                        log("   [ID: $pId] -> Gyújtótáv: ${focal}mm | Szerep: $role")
                    } catch (e: Exception) {
                        log("   [ID: $pId] -> Adatok olvasása: ${e.message}")
                    }
                }
            }

            log("\n3. FIZIKAI MULTI-STREAM TESZT: Wide [$widePhysicalId] + Tele [$telePhysicalId]")
            testLogicalMultiCameraStream()

        } catch (e: Exception) {
            log("HIBA A DIAGNOSZTIKA SORÁN: ${e.message}")
        }
    }

    private fun testLogicalMultiCameraStream() {
        log("-> Kamera [0] megnyitása fizikai al-streamekhez...")
        try {
            cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    log("✓ Logikai Kamera [0] SIKERESEN MEGNYITVA!")
                    log("-> OutputConfiguration felépítése fizikai ID-kkal: Wide [$widePhysicalId] és Tele [$telePhysicalId]...")

                    val readerWide = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
                    val readerTele = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val configWide = OutputConfiguration(readerWide.surface).apply {
                                setPhysicalCameraId(widePhysicalId)
                            }
                            val configTele = OutputConfiguration(readerTele.surface).apply {
                                setPhysicalCameraId(telePhysicalId)
                            }

                            camera.createCaptureSessionByOutputConfigurations(
                                listOf(configWide, configTele),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        log("★★★ TELJES SIKER! ★★★")
                                        log("A Samsung S24 hardveresen engedélyezi a Fő és a Telekamera egyidejű képfolyamát a 'B' módszerrel!")
                                        log("Készen áll a 3D Sztereó Fényképező használatára.")
                                        session.close()
                                        camera.close()
                                        readerWide.close()
                                        readerTele.close()
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        log("A fizikai konfiguráció nem sikerült.")
                                        camera.close()
                                        readerWide.close()
                                        readerTele.close()
                                    }
                                },
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            log("Session hiba: ${e.message}")
                            camera.close()
                        }
                    } else {
                        log("API szint < 28")
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    log("Nem sikerült megnyitni a kamerát (Hiba kód: $error)")
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            log("Biztonsági hiba: ${e.message}")
        }
    }
}
