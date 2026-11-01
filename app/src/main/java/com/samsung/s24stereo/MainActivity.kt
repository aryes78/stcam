package com.samsung.s24stereo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 101
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private lateinit var tvCameraDiagInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnOpenStereoCam = findViewById<Button>(R.id.btnOpenStereoCam)
        val btnOpenStereoVideo = findViewById<Button>(R.id.btnOpenStereoVideo)
        val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)
        tvCameraDiagInfo = findViewById(R.id.tvCameraDiagInfo)

        tvDeviceInfo.text = "Eszköz: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})"

        btnOpenStereoCam.setOnClickListener {
            checkPermissionsAndLaunch(StereoCaptureActivity::class.java)
        }

        btnOpenStereoVideo.setOnClickListener {
            checkPermissionsAndLaunch(StereoVideoActivity::class.java)
        }

        checkAndDisplayCameraDiagnostics()
    }

    private fun checkAndDisplayCameraDiagnostics() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()

        try {
            val cameraIds = cameraManager.cameraIdList
            sb.append("Logikai kamerák: [").append(cameraIds.joinToString(", ")).append("]\n")

            // Hátlapi kamera "0" vizsgálata
            if (cameraIds.contains("0")) {
                val chars0 = cameraManager.getCameraCharacteristics("0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pIds = chars0.physicalCameraIds
                    if (pIds != null && pIds.isNotEmpty()) {
                        sb.append("Fizikai lencsék (kamera 0): [").append(pIds.joinToString(", ")).append("]\n")
                        for (pId in pIds) {
                            try {
                                val pChars = cameraManager.getCameraCharacteristics(pId)
                                val focalArray = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val focal = if (focalArray != null && focalArray.isNotEmpty()) focalArray[0] else 0f
                                sb.append(" -> Lencse [$pId]: ").append(focal).append(" mm")
                                if (focal < 3.0f) sb.append(" (UltraWide)")
                                else if (focal in 4.0f..6.5f) sb.append(" (Főkamera 1x)")
                                else if (focal > 6.5f) sb.append(" (Tele 3x)")
                                sb.append("\n")
                            } catch (e: Exception) {
                                sb.append(" -> Lencse [$pId]: elérhető\n")
                            }
                        }
                    } else {
                        sb.append("Fizikai al-streamek: Egyetlen logikai stream\n")
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("Diagnosztikai hiba: ").append(e.message)
        }

        tvCameraDiagInfo.text = sb.toString().trim()
    }

    private fun checkPermissionsAndLaunch(targetActivity: Class<*>) {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            startActivity(Intent(this, targetActivity))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Minden jogosultság megadva!", Toast.LENGTH_SHORT).show()
            checkAndDisplayCameraDiagnostics()
        } else {
            Toast.makeText(this, "A sztereó felvételhez kamera és mikrofon jogosultság szükséges!", Toast.LENGTH_LONG).show()
        }
    }
}