package com.driveone.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** DRIVE ONE V3. Offline dashboard; all buttons are backed by native actions. */
@Suppress("DEPRECATION")
class MainActivity : Activity(), LocationListener, TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("drive_one_v3", MODE_PRIVATE) }
    private lateinit var dashboard: WebView
    private var pageReady = false
    private var refreshActive = false
    private var locationManager: LocationManager? = null
    private var tracking = false
    private var gpsState = "EN ATTENTE"
    private var speedKmh = 0
    private var lastLocationAt = 0L
    private var lastGoodLocation: Location? = null
    private var tripMeters = 0f
    private var tripStart = 0L
    private var motorEnabled = true
    private var voiceEnabled = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastWelcome = 0L
    private var carWifiConnected = false
    private var currentCarStatus = "A CONFIGURER"
    private var lastBluetooth = "A VERIFIER"
    private var lastBattery = "--%"

    private val refresh = object : Runnable {
        override fun run() {
            if (!refreshActive) return
            if (pageReady) {
                if (lastLocationAt > 0 && SystemClock.elapsedRealtime() - lastLocationAt > 12000L) {
                    gpsState = "RECHERCHE"
                    speedKmh = 0
                }
                refreshCarWifi()
                refreshBluetooth()
                refreshBattery()
                redraw()
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // No FLAG_KEEP_SCREEN_ON: let ScreenOnAuto manage the phone panel.
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        motorEnabled = prefs.getBoolean("engine_enabled", true)
        voiceEnabled = prefs.getBoolean("voice_enabled", true)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        tripStart = SystemClock.elapsedRealtime()

        dashboard = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            addJavascriptInterface(DashboardActions(), "Native")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    redraw()
                }
            }
            loadUrl("file:///android_asset/cockpit.html")
        }
        setContentView(dashboard)
        tts = TextToSpeech(this, this)
    }

    private inner class DashboardActions {
        @JavascriptInterface
        fun action(action: String) {
            runOnUiThread { respondTo(action) }
        }
    }

    private fun respondTo(action: String) {
        when (action) {
            "gps" -> showGps()
            "settings" -> showSettings()
            "info" -> showTripInfo()
            "wifi" -> configureCarWifi()
            "engine" -> {
                motorEnabled = !motorEnabled
                prefs.edit().putBoolean("engine_enabled", motorEnabled).apply()
                updateSportSound()
                redraw()
                toast(if (motorEnabled) "Sport Sound activ\u00e9" else "Sport Sound arr\u00eat\u00e9")
            }
            "engine_test" -> testSportSound()
            "bluetooth" -> safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "battery" -> showBattery()
        }
    }

    private fun openApp(pkg: String, displayName: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) safeStart(intent) else toast("$displayName n'est pas install\u00e9")
    }

    private fun safeStart(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("Impossible d'ouvrir cette fonction") }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun showGps() {
        val fix = if (gpsState == "CONNECTE") "Signal GPS re\u00e7u" else "Position GPS en attente"
        AlertDialog.Builder(this)
            .setTitle("GPS / Vitesse")
            .setMessage("$fix\nVitesse : $speedKmh km/h\nDistance parcourue : ${"%.1f".format(Locale.FRANCE, tripMeters / 1000f)} km\n\nLa vitesse provient du GPS du t\u00e9l\u00e9phone.")
            .setPositiveButton("Waze") { _, _ -> openApp("com.waze", "Waze") }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showBattery() {
        AlertDialog.Builder(this).setTitle("Batterie du t\u00e9l\u00e9phone")
            .setMessage("Niveau actuel : $lastBattery\nIl ne s'agit pas de la batterie du v\u00e9hicule.")
            .setPositiveButton("OK", null).show()
    }

    private fun showTripInfo() {
        val elapsed = ((SystemClock.elapsedRealtime() - tripStart) / 60000L).coerceAtLeast(0L)
        val wifi = prefs.getString("car_wifi", "").orEmpty()
        val info = "Vitesse GPS : $speedKmh km/h\n" +
            "GPS : $gpsState\n" +
            "Distance du trajet : ${"%.1f".format(Locale.FRANCE, tripMeters / 1000f)} km\n" +
            "Dur\u00e9e de session : $elapsed min\n" +
            "Batterie du t\u00e9l\u00e9phone : $lastBattery\n" +
            "Wi-Fi voiture : ${if (wifi.isEmpty()) "Non configur\u00e9" else currentCarStatus}\n\n" +
            "Aucune donn\u00e9e moteur, carburant ou temp\u00e9rature voiture sans OBD."
        AlertDialog.Builder(this).setTitle("Infos auto / trajet")
            .setMessage(info).setPositiveButton("OK", null).show()
    }

    private fun showSettings() {
        val options = arrayOf(
            "Wi-Fi de la voiture",
            "Voix d'accueil : ${if (voiceEnabled) "ON" else "OFF"}",
            "Tester la voix",
            "Choisir une voix fran\u00e7aise",
            "Son moteur : ${if (motorEnabled) "ON" else "OFF"}",
            "Tester le son moteur (4 secondes)",
            "R\u00e9glages GPS",
            "R\u00e9glages Bluetooth"
        )
        AlertDialog.Builder(this).setTitle("Param\u00e8tres DRIVE ONE")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> configureCarWifi()
                    1 -> { voiceEnabled = !voiceEnabled; prefs.edit().putBoolean("voice_enabled", voiceEnabled).apply(); toast("Voix : ${if (voiceEnabled) "ON" else "OFF"}") }
                    2 -> welcome(true)
                    3 -> selectVoice()
                    4 -> { motorEnabled = !motorEnabled; prefs.edit().putBoolean("engine_enabled", motorEnabled).apply(); updateSportSound(); redraw() }
                    5 -> testSportSound()
                    6 -> safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    7 -> safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun configureCarWifi() {
        val field = EditText(this).apply {
            hint = "Nom exact du Wi-Fi (SSID)"
            setSingleLine(true)
            setText(prefs.getString("car_wifi", ""))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi de la voiture")
            .setMessage("Saisis le nom du Wi-Fi utilis\u00e9 par ton \u00e9cran. L'accueil vocal se lance quand ce r\u00e9seau est d\u00e9tect\u00e9, pendant que DRIVE ONE est ouvert.")
            .setView(field)
            .setPositiveButton("Enregistrer") { _, _ ->
                val ssid = field.text.toString().trim().trim('"')
                prefs.edit().putString("car_wifi", ssid).apply()
                carWifiConnected = false
                refreshCarWifi()
                redraw()
            }.setNeutralButton("Wi-Fi Android") { _, _ -> safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun selectVoice() {
        val speech = tts ?: run { toast("Synth\u00e8se vocale indisponible"); return }
        if (!ttsReady) { toast("Voix non initialis\u00e9e"); return }
        val voices = speech.voices.orEmpty().filter { it.locale.language == "fr" }
            .sortedBy { it.name }
        if (voices.isEmpty()) { toast("Aucune voix fran\u00e7aise install\u00e9e"); return }
        val labels = voices.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Voix fran\u00e7aise")
            .setItems(labels) { _, which ->
                val selected = voices[which]
                prefs.edit().putString("voice_name", selected.name).apply()
                speech.voice = selected
                welcome(true)
            }.setNegativeButton("Fermer", null).show()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        val engine = tts ?: return
        engine.language = Locale.FRANCE
        val voices = engine.voices.orEmpty().filter { it.locale.language == "fr" }
        val configuredName = prefs.getString("voice_name", "").orEmpty()
        val configured = voices.firstOrNull { it.name == configuredName }
        val female = voices.firstOrNull {
            val n = it.name.lowercase(Locale.ROOT)
            n.contains("female") || n.contains("femme") || n.contains("woman")
        }
        val selected = configured ?: female
        if (selected != null) engine.voice = selected
        welcome(false)
    }

    private fun welcome(force: Boolean) {
        if (!ttsReady || (!voiceEnabled && !force)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastWelcome < 20000L) return
        lastWelcome = now
        tts?.speak("Bienvenue \u00e0 bord. Drive One est pr\u00eat. Bonne route.", TextToSpeech.QUEUE_FLUSH, null, "driveone_welcome")
    }

    override fun onResume() {
        super.onResume()
        if (::dashboard.isInitialized) dashboard.onResume()
        if (!refreshActive) {
            refreshActive = true
            handler.post(refresh)
        }
        startGps()
        if (::dashboard.isInitialized && pageReady) redraw()
        updateSportSound()
    }

    private fun updateSportSound() {
        val action = if (motorEnabled) SportSoundService.ACTION_START else SportSoundService.ACTION_STOP
        try {
            val intent = Intent(this, SportSoundService::class.java).setAction(action)
            if (motorEnabled) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) { toast("Impossible de lancer le moteur audio") }
    }

    private fun testSportSound() {
        try {
            startForegroundService(Intent(this, SportSoundService::class.java)
                .setAction(SportSoundService.ACTION_TEST))
            toast("Test moteur pendant 4 secondes. V\u00e9rifie le volume multim\u00e9dia")
        } catch (_: Exception) { toast("Test audio indisponible") }
    }

    private fun startGps() {
        if (tracking) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsState = "AUTORISATION"
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }
        val manager = locationManager ?: return
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsState = "GPS DESACTIVE"
                redraw()
                return
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            tracking = true
            gpsState = "RECHERCHE"
        } catch (_: SecurityException) {
            gpsState = "AUTORISATION"
        } catch (_: Exception) {
            gpsState = "INDISPONIBLE"
        }
        redraw()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { startGps(); if (motorEnabled) updateSportSound() }
            else { gpsState = "NON AUTORISE"; redraw() }
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        lastLocationAt = now
        gpsState = "CONNECTE"
        speedKmh = if (location.hasSpeed()) max(0, (location.speed * 3.6f).toInt()) else 0

        val previous = lastGoodLocation
        if (location.hasAccuracy() && location.accuracy <= 35f) {
            if (previous != null) {
                val meters = previous.distanceTo(location)
                val age = (location.time - previous.time).coerceAtLeast(1L) / 1000f
                if (meters in 1f..100f && meters / age < 65f) tripMeters += meters
            }
            lastGoodLocation = location
        }
        redraw()
    }

    private fun refreshCarWifi() {
        val configured = prefs.getString("car_wifi", "").orEmpty().trim()
        if (configured.isEmpty()) {
            currentCarStatus = "A CONFIGURER"
            carWifiConnected = false
            return
        }
        val actual = try { findConnectedWifiSsid() } catch (_: Exception) { null }
        val connected = actual?.equals(configured, ignoreCase = true) == true
        currentCarStatus = if (connected) "CONNECTE" else "NON CONNECTE"
        if (connected && !carWifiConnected) welcome(false)
        carWifiConnected = connected
    }

    private fun findConnectedWifiSsid(): String? {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // This also checks Wi-Fi that is not Android's default internet route.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                for (network in connectivity.allNetworks) {
                    val info = connectivity.getNetworkCapabilities(network)?.transportInfo
                    if (info is WifiInfo) {
                        val ssid = cleanSsid(info.ssid)
                        if (ssid != null) return ssid
                    }
                }
            } catch (_: SecurityException) { }
        }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return try { cleanSsid(wifi.connectionInfo?.ssid) } catch (_: SecurityException) { null }
    }

    private fun cleanSsid(value: String?): String? {
        if (value == null || value.equals("<unknown ssid>", true)) return null
        return value.trim().trim('"').takeUnless { it.isEmpty() || it == "0x" }
    }

    private fun refreshBluetooth() {
        // Android 12+ may restrict Bluetooth state without BLUETOOTH_CONNECT.
        lastBluetooth = try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) "INDISPONIBLE"
            else if (adapter.isEnabled) "ACTIF" else "DESACTIVE"
        } catch (_: SecurityException) {
            val enabled = try { Settings.Global.getInt(contentResolver, "bluetooth_on", -1) } catch (_: Exception) { -1 }
            when (enabled) { 1 -> "ACTIF"; 0 -> "DESACTIVE"; else -> "A VERIFIER" }
        }
    }

    private fun refreshBattery() {
        lastBattery = try {
            val service = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = service.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) "$level%" else "--%"
        } catch (_: Exception) { "--%" }
    }

    private fun redraw() {
        if (!pageReady || !::dashboard.isInitialized) return
        val now = Date()
        val clock = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        val date = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(now).uppercase(Locale.FRANCE)
        val json = JSONObject().apply {
            put("clock", clock)
            put("date", date)
            put("gps", gpsState)
            put("wifi", currentCarStatus)
            put("bluetooth", lastBluetooth)
            put("battery", lastBattery)
            put("engine", if (motorEnabled) "ON" else "OFF")
            put("speed", speedKmh)
        }
        dashboard.evaluateJavascript("window.driveUpdate($json);", null)
    }

    override fun onPause() {
        refreshActive = false
        handler.removeCallbacks(refresh)
        if (tracking) {
            try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
            tracking = false
        }
        if (::dashboard.isInitialized) dashboard.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Stop when deliberately switching away while the handset remains awake.
        // Keep playing if ScreenOnAuto blanks the handset display.
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isInteractive) stopService(Intent(this, SportSoundService::class.java))
    }

    override fun onDestroy() {
        // The service is foreground for screen-off playback; the notification has an explicit STOP control.
        // A normal activity destruction should release it as well.
        if (isFinishing) stopService(Intent(this, SportSoundService::class.java))
        handler.removeCallbacks(refresh)
        if (tracking) try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
        tts?.stop()
        tts?.shutdown()
        if (::dashboard.isInitialized) {
            dashboard.removeJavascriptInterface("Native")
            dashboard.destroy()
        }
        super.onDestroy()
    }

}
