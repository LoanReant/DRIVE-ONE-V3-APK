package com.driveone.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var speedText: TextView
    private var voice: TextToSpeech? = null
    private var locationManager: LocationManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        voice = TextToSpeech(this, this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(8, 12, 22))
        }

        val title = TextView(this).apply {
            text = "DRIVE ONE V3"
            textSize = 30f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }

        speedText = TextView(this).apply {
            text = "0 km/h"
            textSize = 58f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 65, 0, 65)
        }

        fun shortcut(label: String, packageName: String): Button {
            return Button(this).apply {
                text = label
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?.let { startActivity(it) }
                }
            }
        }

        layout.addView(title)
        layout.addView(speedText)
        layout.addView(shortcut("OUVRIR WAZE", "com.waze"))
        layout.addView(shortcut("OUVRIR SPOTIFY", "com.spotify.music"))

        setContentView(layout)
        startSpeedTracking()
    }

    private fun startSpeedTracking() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100
            )
            return
        }

        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f
        ) { location: Location ->
            val speed = if (location.hasSpeed()) {
                (location.speed * 3.6f).toInt()
            } else 0
            speedText.text = "$speed km/h"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startSpeedTracking()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            voice?.language = Locale.FRENCH
            voice?.speak(
                "Bienvenue à bord. Drive One est prêt.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "welcome"
            )
        }
    }

    override fun onDestroy() {
        locationManager?.removeUpdates { }
        voice?.shutdown()
        super.onDestroy()
    }
}