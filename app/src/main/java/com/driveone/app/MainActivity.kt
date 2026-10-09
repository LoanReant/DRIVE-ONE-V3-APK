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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    private var lastSpeedMps: Float? = null
    private var lastSpeedTime = 0L
    private var lastEngineSoundAt = 0L
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            addJavascriptInterface(DashboardActions(), "Native")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    redraw()
                }
            }
            loadDataWithBaseURL("https://driveone.local/", HTML, "text/html", "UTF-8", null)
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
            "waze", "gps" -> if (action == "waze") openApp("com.waze", "Waze") else showGps()
            "spotify" -> openApp("com.spotify.music", "Spotify")
            "phone" -> safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
            "settings" -> showSettings()
            "info" -> showTripInfo()
            "wifi" -> configureCarWifi()
            "engine" -> {
                motorEnabled = !motorEnabled
                prefs.edit().putBoolean("engine_enabled", motorEnabled).apply()
                redraw()
                toast(if (motorEnabled) "Son moteur activ\u00e9" else "Son moteur d\u00e9sactiv\u00e9")
            }
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
                    4 -> { motorEnabled = !motorEnabled; prefs.edit().putBoolean("engine_enabled", motorEnabled).apply(); redraw() }
                    5 -> safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    6 -> safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
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
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
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
        if (location.hasSpeed()) {
            val previousSpeed = lastSpeedMps
            val delta = (now - lastSpeedTime) / 1000f
            if (motorEnabled && previousSpeed != null && delta in 0.6f..6.5f &&
                location.speed > 5f && (location.speed - previousSpeed) / delta > 1.75f &&
                now - lastEngineSoundAt > 7500L &&
                (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 1.7f)
            ) {
                lastEngineSoundAt = now
                playEngineSound()
            }
            lastSpeedMps = location.speed
            lastSpeedTime = now
        }
        redraw()
    }

    private fun playEngineSound() {
        // One short synthetic rev, not a real measurement of engine RPM.
        Thread {
            try {
                val sampleRate = 22050
                val count = (sampleRate * 1.05).toInt()
                val pcm = ShortArray(count)
                var phase = 0.0
                for (i in 0 until count) {
                    val t = i.toDouble() / sampleRate
                    val progress = t / 1.05
                    val hz = 70.0 + 145.0 * progress * progress
                    phase += 2.0 * PI * hz / sampleRate
                    val rise = min(1.0, t * 12.0)
                    val fall = min(1.0, (1.05 - t) * 4.0)
                    val env = max(0.0, min(rise, fall))
                    val output = (sin(phase) * 0.53 + sin(phase * 2.0) * 0.20 +
                        sin(phase * 3.02) * 0.12 + sin(phase * 0.52) * 0.15) * env
                    pcm[i] = (output.coerceIn(-1.0, 1.0) * 12500).toInt().toShort()
                }
                val sound = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, count * 2, AudioTrack.MODE_STATIC
                )
                try {
                    sound.write(pcm, 0, pcm.size)
                    sound.play()
                    Thread.sleep(1200)
                } finally { sound.stop(); sound.release() }
            } catch (_: Exception) { }
        }.start()
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

    override fun onDestroy() {
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

    companion object {
        private val HTML = """<!DOCTYPE html>
<html lang="fr"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover"><title>DRIVE ONE V3</title>
<style>
:root{color-scheme:dark;--red:#dd1028;--blood:#9c081e;--white:#f3f2f2;--shade:#8e8f98}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent} html,body{width:100%;height:100%;margin:0;overflow:hidden;background:#040507;color:var(--white);font-family:Arial,'Roboto',sans-serif;user-select:none} button{font-family:inherit;cursor:pointer;color:inherit;border:0}
.screen{position:relative;display:flex;flex-direction:column;width:100%;height:100%;overflow:hidden;background:radial-gradient(ellipse at 50% 49%,rgba(96,4,18,.24) 0%,transparent 43%),linear-gradient(145deg,#090a0d 0%,#040507 43%,#0d090c 72%,#030406 100%)}
.screen:before{content:'';position:absolute;inset:0;pointer-events:none;opacity:.37;background:repeating-linear-gradient(115deg,transparent 0,transparent 12px,rgba(180,185,200,.013) 13px,transparent 14px),repeating-linear-gradient(0deg,rgba(255,255,255,.006) 0,transparent 2px,transparent 7px)}
.top{height:17%;min-height:45px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 2.8%;position:relative;border-bottom:1px solid #952033;background:linear-gradient(180deg,#09090b,#13080d 85%,#150608);z-index:1}
.top:after{content:'';position:absolute;bottom:-2px;left:0;width:100%;height:2px;background:linear-gradient(90deg,transparent,#ec263e 26%,#fff2 45%,#a90622 66%,transparent);box-shadow:0 0 11px #e50930}
.brand{display:flex;flex-direction:column;gap:2px;white-space:nowrap}.brandname{font-size:clamp(17px,3.7vw,44px);font-weight:800;letter-spacing:.20em;line-height:1;color:#f2f4fa;text-shadow:0 1px 9px #a1a4aa33}.brandname .v3{color:#ed1835;letter-spacing:.13em}.brand-sub{font-size:clamp(7px,.96vw,12px);letter-spacing:.40em;color:#aaa0a5}.topright{display:flex;gap:clamp(5px,1.4vw,22px);align-items:center;height:100%}.timeblock{display:flex;flex-direction:column;align-items:flex-end;justify-content:center;margin-right:10px}.time{font-size:clamp(17px,2.7vw,32px);font-weight:600;letter-spacing:.04em}.date{font-size:clamp(7px,.9vw,12px);color:#beb3b9;letter-spacing:.12em}.status{height:65%;border-left:1px solid #5c2631;display:flex;flex-direction:column;align-items:center;justify-content:center;min-width:clamp(48px,7.6vw,105px);padding:0 7px;gap:2px;background:transparent}.status .statusico{font-size:clamp(12px,2vw,24px);color:#ef2c46;text-shadow:0 0 12px #ff123e99}.status .statusname{font-size:clamp(8px,.9vw,12px);letter-spacing:.06em}.status .statusvalue{color:#ff6577;font-size:clamp(7px,.83vw,11px);font-weight:700;white-space:nowrap}
.main{height:64%;min-height:0;flex:none;display:grid;grid-template-columns:34% 24% 42%;position:relative;align-items:center;padding:1.4% 2% 1%;gap:0;z-index:1}.main:before{position:absolute;inset:1% 1%;content:'';border:1px solid #651624aa;pointer-events:none;clip-path:polygon(0 0,44% 0,52% 10%,100% 10%,100% 100%,0 100%);opacity:.6}
.dialpanel{height:100%;min-width:0;display:flex;align-items:center;justify-content:center;position:relative}.dialpanel:before{content:'';position:absolute;width:80%;aspect-ratio:1;border-radius:50%;background:radial-gradient(circle,transparent 34%,#7e081a3c 62%,transparent 75%);filter:blur(14px);pointer-events:none}
.gauge{width:min(100%,min(63vh,32vw));max-height:100%;aspect-ratio:1;overflow:visible;filter:drop-shadow(0 0 7px #a303224d)}.gauge text{font-family:Arial,sans-serif}.centerpanel{position:relative;height:100%;display:flex;align-items:center;justify-content:center}.centerpanel:before{content:'';width:90%;height:83%;position:absolute;border:1px solid #5c1728;clip-path:polygon(33% 0,88% 0,100% 50%,85% 100%,30% 100%,0 50%);box-shadow:0 0 20px #ce102d30}.centerpanel:after{content:'';position:absolute;width:90%;height:80%;border-left:1px solid #ee1742aa;border-right:1px solid #ee174277;transform:skew(-14deg);pointer-events:none}.logo-halo{position:absolute;width:98%;height:98%;border-radius:50%;background:radial-gradient(circle,#f10c2b42 0%,#a7031b1f 34%,transparent 65%);filter:blur(10px)}.renault{height:min(64%,45vw);max-width:86%;aspect-ratio:1;position:relative;display:flex;align-items:center;justify-content:center;filter:drop-shadow(0 0 13px #e90d39c4)}.renault svg{height:100%;width:100%;max-height:260px;max-width:220px}.smallbrand{position:absolute;bottom:5%;color:#8b3441;letter-spacing:.38em;font-size:clamp(7px,.85vw,11px)}
.rightpanel{height:100%;min-height:0;display:flex;flex-direction:column;gap:3.5%;padding:2.3% 0 2.3% 2%;}.launchers{height:72%;display:grid;grid-template-columns:1fr 1fr;gap:3%;min-height:0}.appcard{position:relative;height:100%;min-width:0;overflow:hidden;border:1px solid #a12b3e;border-radius:clamp(9px,1.2vw,20px);background:linear-gradient(145deg,#1f0b13 0%,#101014 40%,#09090c 100%);box-shadow:inset 0 0 24px #8c001e25,0 0 11px #ec0d2d27;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6%;padding:5% 2%}.appcard:before{content:'';position:absolute;inset:0;border-radius:inherit;border:1px solid #ef263f66;pointer-events:none}.appcard .appicon{width:clamp(35px,6.4vw,90px);height:clamp(35px,6.4vw,90px);object-fit:contain;flex:none;filter:drop-shadow(0 4px 10px #000a)}.appcard .appname{font-size:clamp(13px,2vw,25px);font-weight:800;letter-spacing:.045em}.appcard .appdesc{font-size:clamp(9px,1vw,14px);color:#b7a7ab}.appcard:active,.navbtn:active{background:#3f111e}.smallcontrols{height:24%;display:grid;grid-template-columns:1fr 1fr;gap:3%}.ctl{background:linear-gradient(120deg,#100b10,#0a0b10);border:1px solid #80263a;border-radius:clamp(8px,.95vw,15px);box-shadow:inset 0 0 15px #b4113026;display:flex;gap:clamp(3px,1vw,12px);align-items:center;justify-content:center;padding:0 5px}.ctl .icon{color:#fa304d;font-size:clamp(16px,2.3vw,27px)}.ctl .copy{display:flex;flex-direction:column;text-align:left}.ctl small{font-size:clamp(8px,.92vw,12px);color:#aa9b9f}.ctl strong{font-size:clamp(10px,1.1vw,16px);color:#ff455f}.footer{height:19%;position:relative;z-index:2;flex:none;display:grid;grid-template-columns:repeat(5,1fr);gap:1.2%;padding:1.5% 3% 2%;border-top:2px solid #ae1832;background:linear-gradient(180deg,#1a070c 0%,#09090c 39%,#080809 100%);box-shadow:0 -8px 29px #ba082628}.footer:before{content:'';position:absolute;top:-2px;height:3px;left:5%;right:5%;background:linear-gradient(90deg,transparent,#fb304e,#e7d4d4,#ef2747,transparent);filter:blur(2px)}.navbtn{border:1px solid #55202d;border-radius:clamp(5px,1vw,13px);background:linear-gradient(150deg,#171114,#09090c);height:100%;min-width:0;display:flex;flex-direction:column;justify-content:center;align-items:center;gap:6%;box-shadow:inset 0 -2px 0 #98172a55}.navbtn .navicon{height:clamp(15px,3.1vh,36px);width:clamp(15px,3.1vh,36px);fill:none;stroke:#ed2945;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;filter:drop-shadow(0 0 3px #c9153377)}.navbtn span{font-size:clamp(8px,1.17vw,15px);letter-spacing:.06em;white-space:nowrap}.navbtn:after{content:'';height:2px;width:28%;background:#e52642;box-shadow:0 0 7px #e52642}
@media (max-aspect-ratio:1.56){.main{grid-template-columns:37% 21% 42%}.brandname{letter-spacing:.1em}.smallbrand{display:none}.status{min-width:42px}.rightpanel{padding-left:0}.appcard .appdesc{display:none}.ctl small{display:none}.footer{padding-top:2%}.brand-sub{letter-spacing:.15em}}
@media (max-height:350px){.top{height:16%}.main{height:67%}.footer{height:17%;padding:1% 3%}.rightpanel{padding-top:1%;padding-bottom:1%}.appcard{gap:3%}.ctl .icon{font-size:14px}}
@media (orientation:portrait){.top{height:14%;padding:0 3%}.brandname{font-size:18px}.brand-sub{font-size:7px;letter-spacing:.06em}.topright{gap:1px}.timeblock{margin-right:3px}.time{font-size:14px}.date{display:none}.status{min-width:36px;padding:0 2px}.status .statusico{font-size:13px}.status .statusname{font-size:7px}.status .statusvalue{font-size:6px}.main{height:74%;display:flex;flex-direction:column;padding:1% 4%}.dialpanel{height:47%;width:100%}.gauge{width:min(45vh,65vw)}.centerpanel{height:15%;width:100%}.centerpanel:before,.centerpanel:after,.smallbrand{display:none}.renault{height:100%;max-width:30%}.rightpanel{height:38%;width:100%;padding:1%}.appcard{gap:3%}.appcard .appicon{width:35px;height:35px}.smallcontrols{height:25%}.footer{height:12%;padding:2% 2%}.navbtn span{font-size:7px;letter-spacing:0}.navbtn:after{display:none}}
</style></head>
<body><div class="screen">
<header class="top">
  <div class="brand"><div class="brandname">DRIVE ONE <span class="v3">V3</span></div><div class="brand-sub">DIGITAL COCKPIT</div></div>
  <div class="topright"><div class="timeblock"><span class="time" id="clock">--:--</span><span class="date" id="date">--</span></div>
    <button class="status" onclick="go('gps')"><span class="statusico">&#9678;</span><span class="statusname">GPS</span><span class="statusvalue" id="gpsState">EN ATTENTE</span></button>
    <button class="status" onclick="go('bluetooth')"><span class="statusico">&#8904;</span><span class="statusname">BLUETOOTH</span><span class="statusvalue" id="btState">A VERIFIER</span></button>
    <button class="status" onclick="go('wifi')"><span class="statusico">&#9679;</span><span class="statusname">WI-FI AUTO</span><span class="statusvalue" id="wifiState">A CONFIGURER</span></button>
    <button class="status" onclick="go('battery')"><span class="statusico">&#9632;</span><span class="statusname">BATTERIE</span><span class="statusvalue" id="batteryState">--%</span></button>
  </div>
</header>
<section class="main">
  <div class="dialpanel"><svg class="gauge" viewBox="0 0 500 500" xmlns="http://www.w3.org/2000/svg" aria-label="Compteur de vitesse GPS">
   <defs><linearGradient id="ring" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#fb394e"/><stop offset=".46" stop-color="#b30725"/><stop offset="1" stop-color="#460917"/></linearGradient><radialGradient id="glass"><stop stop-color="#090c12"/><stop offset=".77" stop-color="#08080c"/><stop offset="1" stop-color="#220b13"/></radialGradient></defs>
   <circle cx="250" cy="250" r="233" fill="none" stroke="#54202b" stroke-width="2"/>
   <circle cx="250" cy="250" r="216" fill="url(#glass)" stroke="#87818a" stroke-width="2"/>
   <circle cx="250" cy="250" r="203" fill="none" stroke="#1e1219" stroke-width="26"/>
   <circle cx="250" cy="250" r="202" fill="none" stroke="url(#ring)" stroke-width="15" stroke-dasharray="940 330" stroke-linecap="round" transform="rotate(135 250 250)"/>
   <g id="tickMarks"></g>
   <circle cx="250" cy="250" r="128" fill="#09090c" stroke="#30232b" stroke-width="3"/>
   <text x="250" y="236" font-size="127" fill="#f3f3f5" font-weight="300" text-anchor="middle" id="speedNum">0</text>
   <text x="250" y="293" font-size="28" fill="#9fa0a7" text-anchor="middle" letter-spacing="5">KM/H</text>
   <text x="250" y="351" font-size="15" fill="#c25b6e" text-anchor="middle" letter-spacing="2">VITESSE GPS</text>
   <text x="250" y="375" font-size="14" fill="#e1465d" text-anchor="middle" id="gpsDial">EN ATTENTE</text>
  </svg></div>
  <div class="centerpanel"><div class="logo-halo"></div><div class="renault"><svg xmlns="http://www.w3.org/2000/svg" viewBox="255 138 1005 1257"><defs><linearGradient id="metal" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#8c818a"/><stop offset=".20" stop-color="#31262d"/><stop offset=".47" stop-color="#07070a"/><stop offset=".75" stop-color="#6a434e"/><stop offset="1" stop-color="#10090e"/></linearGradient></defs><path d="M 607 141 L 591 151 L 564 189 L 397 474 L 269 718 L 256 749 L 255 778 L 293 863 L 404 1071 L 541 1306 L 592 1383 L 619 1393 L 902 1392 L 920 1384 L 944 1351 L 1149 997 L 1253 795 L 1259 771 L 1256 746 L 1098 442 L 971 225 L 916 146 L 906 141 L 864 139 Z M 884 167 L 885 174 L 819 309 L 773 419 L 939 751 L 941 776 L 781 1096 L 661 1317 L 628 1365 L 740 1114 L 586 812 L 573 780 L 571 758 L 600 691 L 750 398 L 841 232 Z" fill="url(#metal)" fill-rule="evenodd" stroke="#f32d49" stroke-width="9"/></svg></div><div class="smallbrand">RENAULT</div></div>
  <div class="rightpanel">
   <div class="launchers">
     <button class="appcard" onclick="go('waze')"><svg class="appicon" viewBox="0 0 100 100"><path d="M48 10c-23 0-39 16-39 38 0 11 4 22 14 29l-4 11 16-4c4 2 8 2 13 2 24 0 44-18 44-40S72 10 48 10Z" fill="#fff" stroke="#cfcfd2" stroke-width="4"/><circle cx="35" cy="40" r="4" fill="#0d0d0d"/><circle cx="60" cy="40" r="4" fill="#0d0d0d"/><path d="M35 56q15 15 29-2" stroke="#131313" stroke-width="4" fill="none"/><circle cx="37" cy="87" r="7" fill="#fff" stroke="#121212" stroke-width="4"/><circle cx="69" cy="84" r="7" fill="#fff" stroke="#121212" stroke-width="4"/></svg><span class="appname">WAZE</span><span class="appdesc">Navigation en temps r&eacute;el</span></button>
     <button class="appcard" onclick="go('spotify')"><svg class="appicon" viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="#e91836"/><path d="M24 38q28-11 57 9M27 51q28-8 51 9M30 63q23-5 42 9" fill="none" stroke="#0d0b0d" stroke-width="7" stroke-linecap="round"/></svg><span class="appname">SPOTIFY</span><span class="appdesc">Votre musique, votre route</span></button>
   </div>
   <div class="smallcontrols">
     <button class="ctl" onclick="go('engine')"><span class="icon">&#9678;</span><span class="copy"><small>SON MOTEUR</small><strong id="engineState">ON</strong></span></button>
     <button class="ctl" onclick="go('wifi')"><span class="icon">&#9679;</span><span class="copy"><small>WI-FI VOITURE</small><strong id="wifiSmall">A CONFIGURER</strong></span></button>
   </div>
  </div>
</section>
<footer class="footer">
 <button class="navbtn" onclick="go('gps')"><svg class="navicon" viewBox="0 0 24 24"><path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="2.7"/></svg><span>GPS</span></button>
 <button class="navbtn" onclick="go('spotify')"><svg class="navicon" viewBox="0 0 24 24"><path d="M9 18V4l11-2v14"/><circle cx="6" cy="19" r="3"/><circle cx="17" cy="17" r="3"/></svg><span>MUSIQUE</span></button>
 <button class="navbtn" onclick="go('phone')"><svg class="navicon" viewBox="0 0 24 24"><path d="M5 2h5l1 5-3 2c2 4 4 6 8 8l2-3 4 2v5c-1 2-3 2-5 1C8 19 3 14 2 6 2 4 3 2 5 2Z"/></svg><span>T&Eacute;L&Eacute;PHONE</span></button>
 <button class="navbtn" onclick="go('settings')"><svg class="navicon" viewBox="0 0 24 24"><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M19 5l-2 2M7 17l-2 2"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg><span>PARAM&Egrave;TRES</span></button>
 <button class="navbtn" onclick="go('info')"><svg class="navicon" viewBox="0 0 24 24"><path d="M4 10l2-5h12l2 5v9H4zM4 14h16M7 19v3m10-3v3"/><circle cx="7.5" cy="15" r="1"/><circle cx="16.5" cy="15" r="1"/></svg><span>INFOS AUTO</span></button>
</footer></div>
<script>
(function(){
let g=document.getElementById('tickMarks');
for(let i=0;i<=50;i++){
 const t=(135+i*270/50)*Math.PI/180, major=i%5===0;
 const rr1=major?176:186,rr2=198;
 const x1=250+Math.cos(t)*rr1,y1=250+Math.sin(t)*rr1;
 const x2=250+Math.cos(t)*rr2,y2=250+Math.sin(t)*rr2;
 const line=document.createElementNS('http://www.w3.org/2000/svg','line');
 line.setAttribute('x1',x1);line.setAttribute('y1',y1);line.setAttribute('x2',x2);line.setAttribute('y2',y2);line.setAttribute('stroke',major?'#f4dade':'#6f5d67');line.setAttribute('stroke-width',major?2.5:1);g.appendChild(line);
 if(major){const text=document.createElementNS('http://www.w3.org/2000/svg','text');text.setAttribute('x',250+Math.cos(t)*155);text.setAttribute('y',254+Math.sin(t)*155);text.setAttribute('fill','#d4ced2');text.setAttribute('font-size','20');text.setAttribute('text-anchor','middle');text.textContent=i*5;g.appendChild(text);}
}
})();
function go(action){try{if(window.Native&&Native.action){Native.action(action);return;}}catch(e){}console.log('ACTION',action)}
function put(id,value){const el=document.getElementById(id);if(el)el.textContent=String(value)}
window.driveSetSpeed=function(n){put('speedNum',Math.max(0,Math.round(n)))};
window.driveUpdate=function(s){if(s.clock)put('clock',s.clock);if(s.date)put('date',s.date);if(s.gps){put('gpsState',s.gps);put('gpsDial',s.gps);}if(s.bluetooth)put('btState',s.bluetooth);if(s.wifi){put('wifiState',s.wifi);put('wifiSmall',s.wifi)}if(s.battery)put('batteryState',s.battery);if(s.engine)put('engineState',s.engine);if(typeof s.speed==='number')window.driveSetSpeed(s.speed);};
window.driveUpdate({clock:'--:--',date:'',gps:'EN ATTENTE',bluetooth:'A VERIFIER',wifi:'A CONFIGURER',battery:'--%',engine:'ON',speed:0});
</script>
</body></html>
"""
    }
}
