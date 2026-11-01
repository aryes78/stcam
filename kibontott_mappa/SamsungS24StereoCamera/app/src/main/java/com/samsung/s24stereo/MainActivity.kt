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

    private val CAMERA_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnRunDiagnostic = findViewById<Button>(R.id.btnRunDiagnostic)
        val btnOpenStereoCam = findViewById<Button>(R.id.btnOpenStereoCam)
        val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)

        tvDeviceInfo.text = "Eszköz: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"

        btnRunDiagnostic.setOnClickListener {
            checkPermissionsAndLaunch(CameraTesterActivity::class.java)
        }

        btnOpenStereoCam.setOnClickListener {
            checkPermissionsAndLaunch(StereoCaptureActivity::class.java)
        }
    }

    private fun checkPermissionsAndLaunch(targetActivity: Class<*>) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            startActivity(Intent(this, targetActivity))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Kamera jogosultság megadva!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "A sztereó kamerához szükséges a kamera jogosultság!", Toast.LENGTH_LONG).show()
        }
    }
}