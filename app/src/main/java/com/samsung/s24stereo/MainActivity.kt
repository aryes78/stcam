package com.samsung.s24stereo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnRunDiagnostic = findViewById<Button>(R.id.btnRunDiagnostic)
        val btnOpenStereoCam = findViewById<Button>(R.id.btnOpenStereoCam)
        val btnOpenStereoVideo = findViewById<Button>(R.id.btnOpenStereoVideo)
        val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)

        tvDeviceInfo.text = "Eszköz: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"

        btnRunDiagnostic.setOnClickListener {
            checkPermissionsAndLaunch(CameraTesterActivity::class.java)
        }

        btnOpenStereoCam.setOnClickListener {
            checkPermissionsAndLaunch(StereoCaptureActivity::class.java)
        }

        btnOpenStereoVideo.setOnClickListener {
            checkPermissionsAndLaunch(StereoVideoActivity::class.java)
        }
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
        } else {
            Toast.makeText(this, "A sztereó fotóhoz és videóhoz kamera és mikrofon jogosultság szükséges!", Toast.LENGTH_LONG).show()
        }
    }
}