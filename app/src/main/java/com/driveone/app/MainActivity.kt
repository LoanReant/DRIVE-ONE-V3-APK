package com.driveone.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** DRIVE ONE V3. One Kotlin source, no external libraries or artwork. */
class MainActivity : Activity(), TextToSpeech.OnInitListener, LocationListener {
    private val ink = Color.rgb(3, 9, 19)
    private val white = Color.rgb(236, 247, 255)
    private val cyan = Color.rgb(67, 225, 252)
    private val green = Color.rgb(87, 224, 154)
    private val muted = Color.rgb(140, 163, 182)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("driveone_v3", MODE_PRIVATE) }

    private lateinit var speedDial: SpeedDial
    private lateinit var gpsLabel: TextView
    private lateinit var wifiLabel: TextView
    private lateinit var clockLabel: TextView
    private lateinit var engineButton: TextView
    private lateinit var connectionButton: TextView

    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var lastWelcomeAt = 0L
    private var gps: LocationManager? = null
    private var tracking = false
    private var lastSpeed: Float? = null
    private var lastSpeedTime = 0L
    private var lastEngineAt = 0L
    private var engineSound: AudioTrack? = null
    private var wifiWatcher: ConnectivityManager.NetworkCallback? = null
    private var wifiManager: ConnectivityManager? = null
    private var carWifiConnected = false
    private var engineEnabled = true

    private val clockTask = object : Runnable {
        override fun run() {
            if (::clockLabel.isInitialized) clockLabel.text = DateFormat.format("HH:mm", Date())
            mainHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engineEnabled = preferences.getBoolean("engine_enabled", true)
        gps = getSystemService(LOCATION_SERVICE) as LocationManager
        wifiManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        buildDashboard()
        mainHandler.post(clockTask)
        speech = TextToSpeech(this, this)
    }

    private fun dp(number: Int) = (number * resources.displayMetrics.density).toInt()

    private fun title(text: String, size: Float, color: Int = white, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun panelBackground(stroke: Int = Color.rgb(35, 78, 101)): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(20, 43, 64), Color.rgb(11, 25, 44))
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun buildDashboard() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(if (isLandscape) 11 else 22), dp(18), dp(15))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(ink, Color.rgb(11, 28, 48), Color.rgb(4, 12, 27))
            )
        }

        // Android 15+ draws edge-to-edge for apps targeting API 35.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(18) + bars.left,
                    dp(if (isLandscape) 11 else 22) + bars.top,
                    dp(18) + bars.right, dp(15) + bars.bottom)
                insets
            }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(title("DRIVE ONE", 27f, white, true).apply { letterSpacing = 0.10f })
        brand.addView(title("V3   â€¢   DIGITAL COCKPIT", 10f, cyan, true).apply {
            letterSpacing = 0.20f
        })
        head.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        clockLabel = title("--:--", 24f, white, true)
        head.addView(clockLabel)
        root.addView(head)

        val divider = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(cyan, Color.rgb(25, 110, 141), Color.TRANSPARENT)
            )
        }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(14)
            bottomMargin = dp(8)
        })

        val content = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        speedDial = SpeedDial()
        content.addView(speedDial, if (isLandscape) {
            LinearLayout.LayoutParams(0, -1, 1f)
        } else {
            LinearLayout.LayoutParams(-1, 0, 1f)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(2), dp(5), dp(3))
        }
        controls.addView(title("SYSTÃˆME EMBARQUÃ‰", 11f, muted, true).apply {
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
        })
        gpsLabel = title("â—  GPS : EN ATTENTE", 13f, cyan, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        controls.addView(gpsLabel)
        wifiLabel = title("â—‹  WI-FI VOITURE : NON CONFIGURÃ‰", 11f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(13))
        }
        controls.addView(wifiLabel)

        controls.addView(button("WAZE   â†—", cyan) { openApp("com.waze") })
        controls.addView(button("â™«   SPOTIFY   â†—", green) { openApp("com.spotify.music") })

        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        engineButton = button("", cyan, small = true) { toggleEngine() }
        updateEngineButton()
        settingsRow.addView(engineButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            rightMargin = dp(4)
        })
        connectionButton = button("WI-FI AUTO", cyan, small = true) { chooseCarWifi() }
        settingsRow.addView(connectionButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(4)
        })
        controls.addView(settingsRow, LinearLayout.LayoutParams(-1, -2))
        controls.addView(button("VOIX D'ACCUEIL", cyan, small = true) { chooseVoice() })
        controls.addView(title("GPS â€¢ AUDIO â€¢ CONNEXION", 10f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
            letterSpacing = 0.12f
        })

        content.addView(controls, if (isLandscape) {
            LinearLayout.LayoutParams(dp(257), -1)
        } else {
            LinearLayout.LayoutParams(-1, -2)
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshWifiLabel()
    }

    private fun button(label: String, accent: Int, small: Boolean = false, onClick: () -> Unit): TextView {
        return title(label, if (small) 12f else 17f, white, true).apply {
            gravity = Gravity.CENTER
            background = panelBackground(accent)
            setOnClickListener { onClick() }
            if (!small) layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply {
                bottomMargin = dp(10)
            }
            if (small) layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun openApp(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "Application non installÃ©e", Toast.LENGTH_SHORT).show()
    }

    private fun toggleEngine() {
        engineEnabled = !engineEnabled
        preferences.edit().putBoolean("engine_enabled", engineEnabled).apply()
        updateEngineButton()
        if (!engineEnabled) stopEngineSound()
    }

    private fun updateEngineButton() {
        if (::engineButton.isInitialized) engineButton.text = if (engineEnabled) "MOTEUR : ON" else "MOTEUR : OFF"
    }

    private fun chooseCarWifi() {
        val field = EditText(this).apply {
            hint = "Nom du Wi-Fi de la voiture (SSID)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(preferences.getString("car_wifi", ""))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Connexion voiture")
            .setMessage("Saisis le nom exact du Wi-Fi de l'Ã©cran automobile. L'accueil se lance Ã  sa dÃ©tection pendant que DRIVE ONE est ouvert.")
            .setView(field)
            .setPositiveButton("Enregistrer") { _, _ ->
                preferences.edit().putString("car_wifi", field.text.toString().trim().trim('"')).apply()
                carWifiConnected = false
                refreshWifiLabel()
                checkCurrentWifi()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun refreshWifiLabel() {
        if (!::wifiLabel.isInitialized) return
        val ssid = preferences.getString("car_wifi", "").orEmpty()
        wifiLabel.text = when {
            ssid.isBlank() -> "â—‹  WI-FI VOITURE : Ã€ CONFIGURER"
            carWifiConnected -> "â—  VOITURE CONNECTÃ‰E"
            else -> "â—‹  VOITURE : EN ATTENTE"
        }
        wifiLabel.setTextColor(if (carWifiConnected) green else muted)
    }

    private fun chooseVoice() {
        val engine = speech
        if (!speechReady || engine == null) {
            Toast.makeText(this, "SynthÃ¨se vocale indisponible", Toast.LENGTH_SHORT).show()
            return
        }
        val voices = engine.voices?.filter { it.locale.language == "fr" }
            ?.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            .orEmpty()
        if (voices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Voix franÃ§aise")
                .setMessage("Installe une voix franÃ§aise dans les paramÃ¨tres de synthÃ¨se vocale du tÃ©lÃ©phone.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = voices.map { it.name + if (it.isNetworkConnectionRequired) " (rÃ©seau)" else "" }
        AlertDialog.Builder(this)
            .setTitle("Choisir et Ã©couter une voix")
            .setItems(labels.toTypedArray()) { _, selected ->
                engine.voice = voices[selected]
                preferences.edit().putString("tts_voice", voices[selected].name).apply()
                engine.speak("Bienvenue Ã  bord. Drive One est prÃªt.", TextToSpeech.QUEUE_FLUSH, null, "voice_preview")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = speech ?: return
        engine.language = Locale.FRANCE
        engine.setPitch(1.08f)
        engine.setSpeechRate(0.97f)
        val french = engine.voices?.filter { it.locale.language == "fr" }.orEmpty()
        val remembered = preferences.getString("tts_voice", null)
        val selected = french.firstOrNull { it.name == remembered }
            ?: french.firstOrNull { !it.isNetworkConnectionRequired &&
                (it.name.contains("female", true) || it.name.contains("fem", true)) }
        if (selected != null) engine.voice = selected
        speechReady = true
        welcome()
    }

    private fun welcome() {
        if (!speechReady) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWelcomeAt < 10_000) return
        lastWelcomeAt = now
        speech?.speak("Bienvenue Ã  bord. Drive One est prÃªt. Bonne route.", TextToSpeech.QUEUE_FLUSH, null, "drive_welcome")
    }

    override fun onResume() {
        super.onResume()
        startGps()
        startWifiWatcher()
    }

    private fun startGps() {
        if (tracking) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsLabel.text = "â—  GPS : AUTORISATION REQUISE"
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }
        val locationManager = gps ?: return
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gpsLabel.text = "â—  ACTIVER LA LOCALISATION"
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            tracking = true
            gpsLabel.text = "â—  RECHERCHE DU SIGNAL GPS"
        } catch (_: SecurityException) {
            gpsLabel.text = "â—  GPS NON AUTORISÃ‰"
        }
    }

    override fun onLocationChanged(location: Location) {
        val measuredSpeed = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else 0f
        speedDial.speedKmh = (measuredSpeed * 3.6f).toInt().coerceAtLeast(0)
        gpsLabel.text = if (location.hasSpeed()) "â—  GPS CONNECTÃ‰" else "â—  POSITION GPS REÃ‡UE"

        val now = location.elapsedRealtimeNanos
        val previous = lastSpeed
        if (engineEnabled && location.hasSpeed() && previous != null && lastSpeedTime > 0L) {
            val interval = (now - lastSpeedTime) / 1_000_000_000f
            val acceleration = if (interval in 0.5f..5f) (measuredSpeed - previous) / interval else 0f
            val adequateAccuracy = !location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 1.5f
            if (adequateAccuracy && measuredSpeed > 5f && acceleration >= 2.0f &&
                SystemClock.elapsedRealtime() - lastEngineAt > 5_000L) {
                lastEngineAt = SystemClock.elapsedRealtime()
                playEngineSound()
            }
        }
        lastSpeed = if (location.hasSpeed()) measuredSpeed else null
        lastSpeedTime = now
    }

    /** Short, synthesized engine sweep; no external asset or internet needed. */
    private fun playEngineSound() {
        if (!engineEnabled) return
        stopEngineSound()
        val rate = 22_050
        val length = (rate * 0.82).toInt()
        val samples = ShortArray(length)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / rate
            val progress = t / 0.82
            val frequency = 47.0 + 85.0 * progress
            phase += 2.0 * PI * frequency / rate
            val envelope = min(1.0, t * 15.0) * min(1.0, (0.82 - t) * 7.0)
            val engineWave = sin(phase) * 0.55 + sin(phase * 2.0) * 0.26 +
                sin(phase * 3.0) * 0.12 + sin(phase * 0.5) * 0.07
            samples[i] = (engineWave * envelope * 12_000).toInt()
                .coerceIn(-32768, 32767).toShort()
        }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return
            }
            if (track.write(samples, 0, samples.size) != samples.size) {
                track.release()
                return
            }
            track.setVolume(0.34f)
            track.play()
            engineSound = track
            mainHandler.postDelayed({
                if (engineSound === track) stopEngineSound()
            }, 950)
        } catch (_: Exception) {
            stopEngineSound()
        }
    }

    private fun stopEngineSound() {
        val track = engineSound ?: return
        engineSound = null
        try { track.stop() } catch (_: Exception) { }
        try { track.release() } catch (_: Exception) { }
    }

    private fun startWifiWatcher() {
        if (wifiWatcher != null) return
        val manager = wifiManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val watcher = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    mainHandler.post { checkCurrentWifi() }
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    mainHandler.post { checkCarConnection(readSsid(capabilities)) }
                }
                override fun onLost(network: Network) {
                    mainHandler.post { checkCurrentWifi() }
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post { checkCurrentWifi() }
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    mainHandler.post { checkCarConnection(readSsid(capabilities)) }
                }
                override fun onLost(network: Network) {
                    mainHandler.post { checkCurrentWifi() }
                }
            }
        }
        try {
            manager.registerNetworkCallback(request, watcher)
            wifiWatcher = watcher
            checkCurrentWifi()
        } catch (_: Exception) {
            wifiLabel.text = "â—‹  WI-FI : NON DISPONIBLE"
        }
    }

    private fun readSsid(capabilities: NetworkCapabilities?): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val info = capabilities?.transportInfo
            if (info is WifiInfo) return cleanSsid(info.ssid)
        }
        return fallbackSsid()
    }

    @Suppress("DEPRECATION")
    private fun fallbackSsid(): String? {
        return try {
            val info = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo
            cleanSsid(info?.ssid)
        } catch (_: Exception) { null }
    }

    private fun cleanSsid(text: String?): String? {
        val result = text?.trim()?.trim('"')
        return result?.takeUnless { it.isBlank() || it.equals("<unknown ssid>", true) }
    }

    private fun checkCurrentWifi() {
        val activeNetwork = wifiManager?.activeNetwork
        val caps = if (activeNetwork != null) wifiManager?.getNetworkCapabilities(activeNetwork) else null
        val ssid = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) readSsid(caps)
            else fallbackSsid()
        checkCarConnection(ssid)
    }

    private fun checkCarConnection(ssid: String?) {
        val configured = preferences.getString("car_wifi", "").orEmpty().trim()
        if (configured.isBlank()) {
            carWifiConnected = false
            refreshWifiLabel()
            return
        }
        val matched = ssid != null && configured.equals(ssid, ignoreCase = true)
        if (matched && !carWifiConnected) welcome()
        carWifiConnected = matched
        refreshWifiLabel()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startGps()
                checkCurrentWifi()
            } else gpsLabel.text = "â—  GPS : ACCÃˆS REFUSÃ‰"
        }
    }

    override fun onPause() {
        if (tracking) {
            gps?.removeUpdates(this)
            tracking = false
        }
        lastSpeed = null
        lastSpeedTime = 0L
        wifiWatcher?.let { watcher ->
            try { wifiManager?.unregisterNetworkCallback(watcher) } catch (_: Exception) { }
        }
        wifiWatcher = null
        stopEngineSound()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        speech?.stop()
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }

    private inner class SpeedDial : View(this@MainActivity) {
        var speedKmh: Int = 0
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = RectF(-137f, -137f, 137f, 137f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width / 353f, height / 353f).coerceAtLeast(0.05f)
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(scale, scale)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.rgb(35, 71, 92)
            canvas.drawCircle(0f, 0f, 159f, paint)
            paint.strokeWidth = 13f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(27, 55, 77)
            canvas.drawArc(ring, 135f, 270f, false, paint)
            paint.shader = LinearGradient(-137f, -137f, 137f, 137f,
                intArrayOf(Color.rgb(36, 99, 235), cyan), null, Shader.TileMode.CLAMP)
            canvas.drawArc(ring, 135f, 270f * (speedKmh.coerceIn(0, 220) / 220f), false, paint)
            paint.shader = null
            for (index in 0..44) {
                val angle = Math.toRadians(135.0 + 270.0 * index / 44.0)
                val outer = 118.0
                val inner = if (index % 4 == 0) 105.0 else 111.0
                paint.strokeWidth = if (index % 4 == 0) 2.5f else 1.5f
                paint.strokeCap = Paint.Cap.BUTT
                paint.color = if (index <= speedKmh * 44 / 220) cyan else Color.rgb(73, 105, 127)
                canvas.drawLine((cos(angle) * inner).toFloat(), (sin(angle) * inner).toFloat(),
                    (cos(angle) * outer).toFloat(), (sin(angle) * outer).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.color = muted
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 13f
            canvas.drawText("VITESSE GPS", 0f, -57f, paint)
            paint.color = white
            paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            paint.textSize = 96f
            canvas.drawText(speedKmh.toString(), 0f, 33f, paint)
            paint.color = cyan
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = 20f
            canvas.drawText("KM/H", 0f, 69f, paint)
            paint.color = muted
            paint.textSize = 10f
            canvas.drawText("DRIVE ONE  â€¢  V3", 0f, 158f, paint)
            canvas.restore()
        }
    }
}