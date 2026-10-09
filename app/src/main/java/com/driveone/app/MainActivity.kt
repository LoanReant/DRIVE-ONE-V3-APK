package com.driveone.app

importer android.Manifest
importer android.app.Activity
importer android.app.AlertDialog
importer android.bluetooth.BluetoothAdapter
importer android.content.Intent
importer android.content.pm.ActivityInfo
importer android.content.pm.PackageManager
importer android.graphics.Color
importer android.location.Location
importer android.location.LocationListener
importer android.location.LocationManager
importer android.media.AudioFormat
importer android.media.AudioManager
importer android.media.AudioTrack
importer android.net.ConnectivityManager
importer android.net.Uri
importer android.net.wifi.WifiInfo
importer android.net.wifi.WifiManager
importer android.os.BatteryManager
importer android.os.Bundle
importer android.os.Handler
importer android.os.Looper
importer android.os.SystemClock
importer android.provider.Settings
importer android.speech.tts.TextToSpeech
importer android.view.View
importer android.view.WindowManager
importer android.webkit.JavascriptInterface
importer android.webkit.WebView
importer android.webkit.WebViewClient
importer android.widget.EditText
importer android.widget.Toast
import org.json.JSONObject
importer java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** DRIVE ONE V3. Tableau de bord hors ligne ; tous les boutons sont associés à des actions natives. */
@Suppress("DÉPRÉCATION")
classe MainActivity : Activity(), LocationListener, TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("drive_one_v3", MODE_PRIVATE) }
    tableau de bord privé lateinit var : WebView
    variable privée pageReady = false
    var privée refreshActive = false
    var locationManager: LocationManager? = null
    variable privée tracking = false
    private var gpsState = "EN ATTENTE"
    variable privée vitesseKmh = 0
    variable privée lastLocationAt = 0L
    var privée lastGoodLocation: Location? = null
    variable privée tripMeters = 0f
    variable privée tripStart = 0L
    var privée lastSpeedMps: Float? = null
    variable privée lastSpeedTime = 0L
    variable privée lastEngineSoundAt = 0L
    var privé motorEnabled = vrai
    variable privée voiceEnabled = true
    variable privée tts : TextToSpeech ? = nul
    variable privée ttsReady = false
    variable privée lastWelcome = 0L
    var privée carWifiConnected = false
    variable privée currentCarStatus = "UN CONFIGURANT"
    var privée lastBluetooth = "UN VÉRIFICATEUR"
    variable privée lastBattery = "--%"

    val privée refresh = objet : Runnable {
        remplacer fun run() {
            si (!refreshActive) retourner
            si (pageReady) {
                si (lastLocationAt > 0 && SystemClock.elapsedRealtime() - lastLocationAt > 12000L) {
                    gpsState = "RECHERCHE"
                    vitesse km/h = 0
                }
                rafraîchirCarWifi()
                rafraîchirBluetooth()
                rafraîchirBattery()
                redessiner()
            }
            handler.postDelayed(this, 2000L)
        }
    }

    remplacer fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Orientation demandée = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        fenêtre.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        fenêtre.decorView.systemUiVisibility = (
            Afficher.SYSTEM_UI_FLAG_FULLSCREEN ou Afficher.SYSTEM_UI_FLAG_HIDE_NAVIGATION ou
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY ou View.SYSTEM_UI_FLAG_LAYOUT_STABLE ou
            Afficher.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN ou Afficher.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        moteurActivé = prefs.getBoolean("moteur_activé", true)
        voixActivée = prefs.getBoolean("voice_enabled", true)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        tripStart = SystemClock.elapsedRealtime()

        tableau de bord = WebView(this).apply {
            définirCouleurFond(Couleur.NOIR)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            paramètres.autoriserL'accèsAuxFichiers = faux
            paramètres.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            ajouterJavascriptInterface(DashboardActions(), "Native")
            webViewClient = objet : WebViewClient() {
                remplacer la fonction onPageFinished(view: WebView?, url: String?) {
                    pageReady = vrai
                    redessiner()
                }
            }
            chargerDataWithBaseURL("https://driveone.local/", HTML, "text/html", "UTF-8", null)
        }
        définirContentView(tableau de bord)
        tts = TextToSpeech(ceci, ceci)
    }

    classe interne privée DashboardActions {
        @JavascriptInterface
        action amusante(action : Chaîne) {
            runOnUiThread { respondTo(action) }
        }
    }

    fonction privée respondTo(action: String) {
        quand (action) {
            "waze", "gps" -> si (action == "waze") ouvrirApp("com.waze", "Waze") sinon afficherGps()
            "spotify" -> openApp("com.spotify.music", "Spotify")
            "téléphone" -> safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
            "paramètres" -> afficherParamètres()
            "info" -> afficherInfosVoyage()
            "wifi" -> configureCarWifi()
            "moteur" -> {
                moteurActivé = !moteurActivé
                prefs.edit().putBoolean("engine_enabled", motorEnabled).apply()
                redessiner()
                toast(if (motorEnabled) "Son moteur activ\u00e9" else "Son moteur d\u00e9sactiv\u00e9")
            }
            "bluetooth" -> safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "batterie" -> afficherBattery()
        }
    }

    private fun openApp(pkg: String, displayName: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) safeStart(intent) else toast("$displayName n'est pas installé\u00e9")
    }

    private fun safeStart(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("Impossible d'ouvrir cette fonction") }
    }

    fonction privée toast(texte: String) = Toast.makeText(this, texte, Toast.LENGTH_SHORT).show()

    fonction privée showGps() {
        val fix = if (gpsState == "CONNECTE") "Signal GPS re\u00e7u" else "Position GPS en attente"
        AlertDialog.Builder(ceci)
            .setTitle("GPS / Vitesse")
            .setMessage("$fix\nVitesse : $speedKmh km/h\nDistance parcourue : ${"%.1f.format(Locale.FRANCE, tripMeters / 1000f)} km\n\nLa vitesse provient du GPS du t\u00e9l\u00e9phone.")
            .setPositiveButton("Waze") { _, _ -> openApp("com.waze", "Waze") }
            .setNegativeButton("Fermer", null)
            .montrer()
    }

    fonction privée showBattery() {
        AlertDialog.Builder(this).setTitle("Batterie du t\u00e9l\u00e9phone")
            .setMessage("Niveau actuel : $lastBattery\nIl ne s'agit pas de la batterie du véhicule.")
            .setPositiveButton("OK", null).show()
    }

    fonction privée showTripInfo() {
        val elapsed = ((SystemClock.elapsedRealtime() - tripStart) / 60000L).coerceAtLeast(0L)
        val wifi = prefs.getString("car_wifi", "").orEmpty()
        val info = "Vitesse GPS : $speedKmh km/h\n" +
            "GPS : $gpsState\n" +
            "Distance du trajet : ${"%.1f.format(Locale.FRANCE, tripMeters / 1000f)} km\n" +
            "Durée de la session : $elapsed min\n" +
            "Batterie du t\u00e9l\u00e9phone : $lastBattery\n" +
            "Wi-Fi voiture : ${if (wifi.isEmpty()) "Non configur\u00e9" else currentCarStatus}\n\n" +
            "Aucune donn\u00e9e moteur, carburant ou temp\u00e9rature voiture sans OBD."
        AlertDialog.Builder(this).setTitle("Infos auto / trajet")
            .setMessage(info).setPositiveButton("OK", null).show()
    }

    fonction privée showSettings() {
        val options = tableauDe(
            "Wi-Fi de la voiture",
            "Voix d'accueil : ${if (voiceEnabled) "ON" else "OFF"}",
            "Tester la voix",
            "Choisir une voix française",
            "Son moteur : ${if (motorEnabled) "ON" else "OFF"}",
            "R\u00e9glages GPS",
            "Ru00e9glements Bluetooth"
        )
        AlertDialog.Builder(this).setTitle("Paramètres DRIVE ONE")
            .setItems(options) { _, index ->
                quand (index) {
                    0 -> configureCarWifi()
                    1 -> { voiceEnabled = !voiceEnabled; prefs.edit().putBoolean("voice_enabled", voiceEnabled).apply(); toast("Voix : ${if (voiceEnabled) "ON" else "OFF"}") }
                    2 -> bienvenue(vrai)
                    3 -> sélectionnerVoix()
                    4 -> { moteurActivé = !moteurActivé; prefs.edit().putBoolean("moteur_activé", moteurActivé).apply(); redraw() }
                    5 -> safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    6 -> safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }.setNegativeButton("Fermer", null).show()
    }

    fonction privée configureCarWifi() {
        val champ = EditText(this).apply {
            truc = "Nom exact du Wi-Fi (SSID)"
            définirLigneUnique(vrai)
            setText(prefs.getString("car_wifi", ""))
            sélectionnerTout()
        }
        AlertDialog.Builder(ceci)
            .setTitle("Wi-Fi de la voiture")
            .setMessage("Saisis le nom du Wi-Fi utilisé\u00e9 par ton \u00e9cran. L'accueil vocal se lance quand ce r\u00e9seau est d\u00e9tect\u00e9, pendant que DRIVE ONE est ouvert.")
            .setView(champ)
            .setPositiveButton("Enregistreur") { _, _ ->
                val ssid = field.text.toString().trim().trim('"')
                prefs.edit().putString("car_wifi", ssid).apply()
                voitureWifiConnecté = faux
                rafraîchirCarWifi()
                redessiner()
            }.setNeutralButton("Wi-Fi Android") { _, _ -> safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .setNegativeButton("Annuleur", nul)
            .montrer()
    }

    fonction privée selectVoice() {
        val speech = tts ?: run { toast("Synthétiseur vocal indisponible"); return }
        if (!ttsReady) { toast("Voix non initialis\u00e9e"); retour }
        val voices = speech.voices.orEmpty().filter { it.locale.language == "fr" }
            .sortedBy { it.name }
        if (voices.isEmpty()) { toast("Aucune voix française install\u00e9e"); retour }
        val labels = voices.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Voix française")
            .setItems(labels) { _, qui ->
                val sélectionné = voix[qui]
                prefs.edit().putString("voice_name", selected.name).apply()
                voix.parole = sélectionné
                bienvenue(vrai)
            }.setNegativeButton("Fermer", null).show()
    }

    remplacer fun onInit(status: Int) {
        si (statut != TextToSpeech.SUCCESS) retourner
        ttsReady = vrai
        val moteur = tts ?: retourner
        moteur.langue = Locale.FRANCE
        val voices = engine.voices.orEmpty().filter { it.locale.language == "fr" }
        val configuredName = prefs.getString("voice_name", "").orEmpty()
        val configured = voices.firstOrNull { it.name == configuredName }
        val femme = voix.premièreOuNulle {
            val n = it.name.lowercase(Locale.ROOT)
            n.contains("female") || n.contains("femme") || n.contains("woman")
        }
        val sélectionné = configuré ?: femme
        si (sélectionné != null) moteur.voix = sélectionné
        bienvenue(faux)
    }

    bienvenue privée amusante(force : booléen) {
        si (!ttsReady || (!voiceEnabled && !force)) retourner
        val maintenant = SystemClock.elapsedRealtime()
        si (!force && maintenant - dernierBienvenue < 20000L) retourner
        lastWelcome = maintenant
        tts?.speak("Bienvenue \u00e0 bord. Drive One est pr\u00eat. Bonne route.", TextToSpeech.QUEUE_FLUSH, null, "driveone_welcome")
    }

    remplacer la fonction onResume() {
        super.onResume()
        si (::dashboard.isInitialized) dashboard.onResume()
        si (!refreshActive) {
            actualiserActif = vrai
            gestionnaire.post(rafraîchir)
        }
        démarrerGps()
        si (::dashboard.isInitialized && pageReady) redessiner()
    }

    fonction privée startGps() {
        si (suivi) retour
        si (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsState = "AUTORISATION"
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            retour
        }
        val manager = locationManager ?: retourner
        essayer {
            si (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsState = "GPS DÉSACTIVÉ"
                redessiner()
                retour
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            suivi = vrai
            gpsState = "RECHERCHE"
        } catch (_: SecurityException) {
            gpsState = "AUTORISATION"
        } catch (_: Exception) {
            gpsState = "INDISPONIBLE"
        }
        redessiner()
    }

    remplacer la fonction onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        si (requestCode == 102) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
            else { gpsState = "NON AUTORISÉ"; redessiner() }
        }
    }

    remplacer fun onLocationChanged(location: Location) {
        val maintenant = SystemClock.elapsedRealtime()
        dernièrePositionÀ = maintenant
        gpsState = "CONNECTÉ"
        vitesseKmh = si (location.hasSpeed()) max(0, (location.speed * 3.6f).toInt()) sinon 0

        val précédent = dernierbonemplacement
        si (location.hasAccuracy() && location.accuracy <= 35f) {
            si (précédent != nul) {
                val mètres = précédente.distanceTo(emplacement)
                âge de la valeur = (location.time - previous.time).coerceAtLeast(1L) / 1000f
                si (mètres dans 1f..100f && mètres / âge < 65f) tripMeters += mètres
            }
            dernierBonEmplacement = emplacement
        }
        si (location.hasSpeed()) {
            val vitesse_précédente = dernièreVitesseMps
            val delta = (maintenant - derniertempsvitesse) / 1000f
            si (moteurActivé && vitessePrécédente != null && delta dans 0,6f..6,5f &&
                position.vitesse > 5f && (position.vitesse - vitesse précédente) / delta > 1,75f &&
                maintenant - lastEngineSoundAt > 7500L &&
                (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 1.7f)
            ) {
                lastEngineSoundAt = maintenant
                jouerLeSonDuMoteur()
            }
            dernièreVitesseMps = emplacement.vitesse
            lastSpeedTime = maintenant
        }
        redessiner()
    }

    private fun playEngineSound() {
        // Un bref tour synthétique, pas une véritable mesure du régime moteur.
        Fil de discussion {
            essayer {
                val sampleRate = 22050
                val count = (sampleRate * 1.05).toInt()
                val pcm = ShortArray(count)
                var phase = 0.0
                pour (i dans 0 jusqu'à comptage) {
                    val t = i.toDouble() / sampleRate
                    val progress = t / 1,05
                    valeur hz = 70,0 + 145,0 * progression * progression
                    phase += 2,0 * PI * Hz / fréquence d'échantillonnage
                    val rise = min(1.0, t * 12.0)
                    val fall = min(1.0, (1.05 - t) * 4.0)
                    val env = max(0.0, min(rise, fall))
                    val output = (sin(phase) * 0,53 + sin(phase * 2,0) * 0,20 +
                        sin(phase * 3,02) * 0,12 + sin(phase * 0,52) * 0,15) * env
                    pcm[i] = (output.coerceIn(-1.0, 1.0) * 12500).toInt().toShort()
                }
                val son = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, nombre * 2, AudioTrack.MODE_STATIC
                )
                essayer {
                    son.écrire(pcm, 0, pcm.taille)
                    son.jouer()
                    Thread.sleep(1200)
                } finally { sound.stop(); sound.release() }
            } catch (_: Exception) { }
        }.commencer()
    }

    fonction privée refreshCarWifi() {
        val configured = prefs.getString("car_wifi", "").orEmpty().trim()
        si (configuré.estVide()) {
            currentCarStatus = "UN CONFIGURANT"
            voitureWifiConnecté = faux
            retour
        }
        val actual = try { findConnectedWifiSsid() } catch (_: Exception) { null }
        val connected = actual?.equals(configured, ignoreCase = true) == true
        currentCarStatus = si (connecté) "CONNECTE" sinon "NON CONNECTE"
        si (connecté && !carWifiConnected) bienvenue(false)
        voitureWifiConnecté = connecté
    }

    fonction privée findConnectedWifiSsid(): String? {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // Ceci vérifie également les réseaux Wi-Fi qui ne sont pas la route Internet par défaut d'Android.
        si (android.os.Build.VERSION.SDK_INT >= 29) {
            essayer {
                pour (réseau dans connectivity.allNetworks) {
                    val info = connectivity.getNetworkCapabilities(network)?.transportInfo
                    si (info est WifiInfo) {
                        val ssid = cleanSsid (info.ssid)
                        si (ssid != null) retourner ssid
                    }
                }
            } catch (_: SecurityException) { }
        }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return try { cleanSsid(wifi.connectionInfo?.ssid) } catch (_: SecurityException) { null }
    }

    fonction privée cleanSsid(valeur : String?): String? {
        si (valeur == null || valeur.equals("<ssid inconnu>", true)) retourner null
        return value.trim().trim('"').takeUnless { it.isEmpty() || it == "0x" }
    }

    private fun refreshBluetooth() {
        // Android 12+ peut restreindre l'état Bluetooth sans BLUETOOTH_CONNECT.
        dernierBluetooth = essayer {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adaptateur == null) "INDISPONIBLE"
            sinon si (adapter.isEnabled) "ACTIF" sinon "DÉSACTIVÉ"
        } catch (_: SecurityException) {
            val enabled = try { Settings.Global.getInt(contentResolver, "bluetooth_on", -1) } catch (_: Exception) { -1 }
            lorsque (activé) { 1 -> "ACTIF"; 0 -> "DÉSACTIVÉ"; sinon -> "UN VÉRIFICATEUR" }
        }
    }

    fonction privée refreshBattery() {
        dernièreBattery = essayer {
            val service = getSystemService(BATTERY_SERVICE) as BatteryManager
            val niveau = service.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            si (niveau dans 0..100) "$niveau%" sinon "--%"
        } catch (_: Exception) { "--%" }
    }

    fonction privée redessiner() {
        si (!pageReady || !::dashboard.isInitialized) retourner
        val maintenant = Date()
        val clock = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        val date = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(now).uppercase(Locale.FRANCE)
        val json = JSONObject().apply {
            mettre("horloge", horloge)
            mettre("date", date)
            mettre("gps", gpsState)
            mettre("wifi", currentCarStatus)
            mettre("bluetooth", dernierBluetooth)
            mettre("batterie", dernièreBatterie)
            mettre("moteur", si (moteurActivé) "ON" sinon "OFF")
            mettre("vitesse", vitesseKmh)
        }
        dashboard.evaluateJavascript("window.driveUpdate($json);", null)
    }

    remplacer fun onPause() {
        actualiserActif = faux
        gestionnaire.supprimerCallbacks(rafraîchir)
        si (suivi) {
            try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
            suivi = faux
        }
        si (::dashboard.isInitialized) dashboard.onPause()
        super.onPause()
    }

    remplacer fun onDestroy() {
        gestionnaire.supprimerCallbacks(rafraîchir)
        if (tracking) try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
        tts?.stop()
        tts?.fermer()
        si (::dashboard.isInitialized) {
            tableau de bord.supprimerJavascriptInterface("Native")
            tableau de bord.détruire()
        }
        super.onDestroy()
    }

    objet compagnon {
        valeur privée HTML = """<!DOCTYPE html>
<html lang="fr"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover"><title>DRIVE ONE V3</title>
<style>
:root{color-scheme:dark;--red:#dd1028;--blood:#9c081e;--white:#f3f2f2;--shade:#8e8f98}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent} html,body{width:100%;height:100%;margin:0;overflow:hidden;background:#040507;color:var(--white);font-family:Arial,'Roboto',sans-serif;user-select:none} button{font-family:inherit;cursor:pointer;color:inherit;border:0}
.screen{position:relative;display:flex;flex-direction:column;width:100%;height:100%;overflow:hidden;background:radial-gradient(ellipse at 50% 49%,rgba(96,4,18,.24) 0%,transparent 43%),linear-gradient(145deg,#090a0d 0%,#040507 43%,#0d090c 72%,#030406 100%)}
.screen:before{content:'';position:absolute;inset:0;pointer-events:none;opacity:.37;background:repeating-linear-gradient(115deg,transparent 0,transparent 12px,rgba(180,185,200,.013) 13px,transparent 14px),repeating-linear-gradient(0deg,rgba(255,255,255,.006) 0,transparent 2px,transparent 7px)}
.top{height:17%;min-height:45px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 2.8%;position:relative;border-bottom:1px solid #952033;background:linear-gradient(180deg,#09090b,#13080d 85%,#150608);z-index:1}
.top:after{content:'';position:absolute;bottom:-2px;left:0;width:100%;height:2px;background:linear-gradient(90deg,transparent,#ec263e 26%,#fff2 45%,#a90622 66%,transparent);box-shadow:0 0 11px #e50930}
.brand{display:flex;flex-direction:column;gap:2px;white-space:nowrap}.brandname{font-size:clamp(17px,3.7vw,44px);font-weight:800;letter-spacing:.20em;line-height:1;color:#f2f4fa;text-shadow:0 1px 9px #a1a4aa33}.brandname .v3{color:#ed1835;letter-spacing:.13em}.brand-sub{font-size:clamp(7px,.96vw,12px);letter-spacing:.40em;color:#aaa0a5}.topright{display:flex;gap:clamp(5px,1.4vw,22px);align-items:center;height:100%}.timeblock{display:flex;flex-direction:column; align-items:flex-end;justify-content:center;margin-right:10px}.time{font-size:clamp(17px,2.7vw,32px);font-weight:600;letter-spacing:.04em}.date{font-size:clamp(7px,.9vw,12px);color:#beb3b9;letter-spacing:.12em}.status{height:65%;border-left:1px solid #5c2631;display:flex;flex-direction:column;align-items:center;justify-content:center;min-width:clamp(48px,7.6vw,105px);padding:0 7px;gap:2px;background:transparent}.status .statusico{font-size:clamp(12px,2vw,24px);color:#ef2c46;text-shadow:0 0 12px #ff123e99}.status .statusname{font-size:clamp(8px,.9vw,12px);letter-spacing:.06em}.status .statusvalue{color:#ff6577;font-size:clamp(7px,.83vw,11px);font-weight:700;white-space:nowrap}
.main{height:64%;min-height:0;flex:none;display:grid;grid-template-columns:34% 24% 42%;position:relative;align-items:center;padding:1.4% 2% 1%;gap:0;z-index:1}.main:before{position:absolute;inset:1% 1%;content:'';border:1px solid #651624aa;pointer-events:none;clip-path:polygon(0 0,44% 0,52% 10%,100% 10%,100% 100%,0 100%);opacity:.6}
.dialpanel{height:100%;min-width:0;display:flex;align-items:center;justify-content:center;position:relative}.dialpanel:before{content:'';position:absolute;width:80%;aspect-ratio:1;border-radius:50%;background:radial-gradient(circle,transparent 34%,#7e081a3c 62%,transparent 75%);filter:blur(14px);pointer-events:none}
.gauge{width:min(100%,min(63vh,32vw));max-height:100%;aspect-ratio:1;overflow:visible;filter:drop-shadow(0 0 7px #a303224d)}.gauge text{font-family:Arial,sans-serif}.centerpanel{position:relative;height:100%;display:flex;align-items:center;justify-content:center}.centerpanel:before{content:'';width:90%;height:83%;position:absolute;border:1px solid #5c1728;clip-path:polygon(33% 0,88% 0,100% 50%,85% 100%,30% 100%,0 50%);box-shadow:0 0 20px #ce102d30}.centerpanel:after{content:'';position:absolute;width:90%;height:80%;border-left:1px solid #ee1742aa;border-right:1px solid #ee174277;transform:skew(-14deg);pointer-events:none}.logo-halo{position:absolute;width:98%;height:98%;border-radius:50%;background:radial-gradient(circle,#f10c2b42 0%,#a7031b1f 34%,transparent 65%);filter:blur(10px)}.renault{height:min(64%,45vw);max-width:86%;aspect-ratio:1;position:relative;display:flex;align-items:center;justify-content:center;filter:drop-shadow(0 0 13px #e90d39c4)}.renault svg{height:100%;width:100%;max-height:260px;max-width:220px}.smallbrand{position:absolute;bottom:5%;color:#8b3441;letter-spacing:.38em;font-size:clamp(7px,.85vw,11px)}
.rightpanel{height:100%;min-height:0;display:flex;flex-direction:column;gap:3.5%;padding:2.3% 0 2.3% 2%;}.launchers{height:72%;display:grid;grid-template-columns:1fr 1fr;gap:3%;min-height:0}.appcard{position:relative;height:100%;min-width:0;overflow:hidden;border:1px solid #a12b3e;border-radius:clamp(9px,1.2vw,20px);background:linear-gradient(145deg,#1f0b13 0%,#101014 40%,#09090c 100%);box-shadow:inset 0 0 24px #8c001e25,0 0 11px #ec0d2d27;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6%;padding:5% 2%}.appcard:before{content:'';position:absolute;inset:0;border-radius:inherit;border:1px solid #ef263f66;pointer-events:none}.appcard .appicon{width:clamp(35px,6.4vw,90px);height:clamp(35px,6.4vw,90px);object-fit:contain;flex:none;filter:drop-shadow(0 4px 10px #000a)}.appcard .appname{font-size:clamp(13px,2vw,25px);font-weight:800;letter-spacing:.045em}.appcard .appdesc{font-size:clamp(9px,1vw,14px);color:#b7a7ab}.appcard:active,.navbtn:active{background:#3f111e}.smallcontrols{height:24%;display:grid;grid-template-columns:1fr 1fr;gap:3%}.ctl{background:linear-gradient(120deg,#100b10,#0a0b10);border:1px solid #80263a;border-radius:clamp(8px,.95vw,15px);box-shadow:inset 0 0 15px #b4113026;display:flex;gap:clamp(3px,1vw,12px);align-items:center;justify-content:center;padding:0 5px}.ctl .icon{color:#fa304d;font-size:clamp(16px,2.3vw,27px)}.ctl .copy{display:flex;flex-direction:column;text-align:left}.ctl small{font-size:clamp(8px,.92vw,12px);color:#aa9b9f}.ctl strong{font-size:clamp(10px,1.1vw,16px);color:#ff455f}.footer{height:19%;position:relative;z-index:2;flex:none;display:grid;grid-template-columns:repeat(5,1fr);gap:1.2%;padding:1.5% 3% 2%;border-top:2px solid #ae1832;background:linear-gradient(180deg,#1a070c 0%,#09090c 39%,#080809 100%);box-shadow:0 -8px 29px #ba082628}.footer:before{content:'';position:absolute;top:-2px;height:3px;left:5%;right:5%;background:linear-gradient(90deg,transparent,#fb304e,#e7d4d4,#ef2747,transparent);filter:blur(2px)}.navbtn{border:1px solid #55202d;border-radius:clamp(5px,1vw,13px);background:linear-gradient(150deg,#171114,#09090c);height:100%;min-width:0;display:flex;flex-direction:column;justify-content:center;align-items:center;gap:6%;box-shadow:inset 0 -2px 0 #98172a55}.navbtn .navicon{height:clamp(15px,3.1vh,36px);width:clamp(15px,3.1vh,36px);fill:none;stroke:#ed2945;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;filter:drop-shadow(0 0 3px #c9153377)}.navbtn span{font-size:clamp(8px,1.17vw,15px);letter-spacing:.06em;white-space:nowrap}.navbtn:after{content:'';height:2px;width:28%;background:#e52642;box-shadow:0 0 7px #e52642}
@media (max-aspect-ratio:1.56){.main{grid-template-columns:37% 21% 42%}.brandname{letter-spacing:.1em}.smallbrand{display:none}.status{min-width:42px}.rightpanel{padding-left:0}.appcard .appdesc{display:none}.ctl small{display:none}.footer{padding-top:2%}.brand-sub{letter-spacing:.15em}}
@media (max-height:350px){.top{height:16%}.main{height:67%}.footer{height:17%;padding:1% 3%}.rightpanel{padding-top:1%;padding-bottom:1%}.appcard{gap:3%}.ctl .icon{font-size:14px}}
@media (orientation:portrait){.top{height:14%;padding:0 3%}.brandname{font-size:18px}.brand-sub{font-size:7px;letter-spacing:.06em}.topright{gap:1px}.timeblock{margin-right:3px}.time{font-size:14px}.date{display:none}.status{min-width:36px;padding:0 2px}.status .statusico{font-size:13px}.status .statusname{font-size:7px}.status .statusvalue{font-size:6px}.main{height:74%;display:flex;flex-direction:column;padding:1% 4%}.dialpanel{hauteur:47%;largeur:100%}.gauge{largeur:min(45vh,65vw)}.centerpanel{hauteur:15%;largeur:100%}.centerpanel:before,.centerpanel:after,.smallbrand{affichage:aucun}.renault{hauteur:100%;largeur-max:30%}.rightpanel{hauteur:38%;largeur:100%;padding:1%}.appcard{écart:3%}.appcard .appicon{largeur:35px;hauteur:35px}.smallcontrols{hauteur:25%}.footer{hauteur:12%;padding:2% 2%}.navbtn span{taille-police:7px;espacement-lettres:0}.navbtn:after{affichage:aucun}}
</style></head>
<body><div class="screen">
<header class="top">
  <div class="brand"><div class="brandname">DRIVE ONE <span class="v3">V3</span></div><div class="brand-sub">COCKPIT NUMÉRIQUE</div></div>
  <div class="topright"><div class="timeblock"><span class="time" id="clock">--:--</span><span class="date" id="date">--</span></div>
    <button class="status" onclick="go('gps')"><span class="statusico">◎</span><span class="statusname">GPS</span><span class="statusvalue" id="gpsState">EN ATTENTE</span></button>
    <button class="status" onclick="go('bluetooth')"><span class="statusico">⋈</span><span class="statusname">BLUETOOTH</span><span class="statusvalue" id="btState">UN VÉRIFICATEUR</span></button>
    <button class="status" onclick="go('wifi')"><span class="statusico">●</span><span class="statusname">WI-FI AUTO</span><span class="statusvalue" id="wifiState">UN CONFIGURANT</span></button>
    <button class="status" onclick="go('battery')"><span class="statusico">■</span><span class="statusname">BATTERIE</span><span class="statusvalue" id="batteryState">--%</span></button>
  </div>
</header>
<section class="main">
  <div class="dialpanel"><svg class="gauge" viewBox="0 0 500 500" xmlns="http://www.w3.org/2000/svg" aria-label="Compteur de vitesse GPS">
   <defs><linearGradient id="ring" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#fb394e"/><stop offset=".46" stop-color="#b30725"/><stop offset="1" stop-color="#460917"/></linearGradient><radialGradient id="glass"><stop stop-color="#090c12"/><stop offset=".77" stop-color="#08080c"/><stop offset="1" stop-color="#220b13"/></radialGradient></defs>
   <circle cx="250" cy="250" r="233" fill="none" stroke="54202b" stroke-width="2"/>
   <circle cx="250" cy="250" r="216" fill="url(#glass)" stroke="#87818a" stroke-width="2"/>
   <circle cx="250" cy="250" r="203" fill="none" stroke="1e1219" stroke-width="26"/>
   <circle cx="250" cy="250" r="202" fill="none" stroke="url(#ring)" stroke-width="15" stroke-dasharray="940 330" stroke-linecap="round" transform="rotate(135 250 250)"/>
   <g id="tickMarks"></g>
   <circle cx="250" cy="250" r="128" fill="#09090c" stroke="#30232b" stroke-width="3"/>
   <text x="250" y="236" font-size="127" fill="f3f3f5" font-weight="300" text-anchor="middle" id="speedNum">0</text>
   <text x="250" y="293" font-size="28" fill="#9fa0a7" text-anchor="middle" letter-spacing="5">KM/H</text>
   <text x="250" y="351" font-size="15" fill="#c25b6e" text-anchor="middle" letter-spacing="2">VITESSE GPS</text>
   <text x="250" y="375" font-size="14" fill="#e1465d" text-anchor="middle" id="gpsDial">EN ATTENTE</text>
  </svg></div>
  <div class="centerpanel"><div class="logo-halo"></div><div class="renault"><svg xmlns="http://www.w3.org/2000/svg" viewBox="255 138 1005 1257"><defs><linearGradient id="metal" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#8c818a"/><stop offset=".20" stop-color="#31262d"/><stop offset=".47" stop-color="#07070a"/><stop offset=".75" stop-color="#6a434e"/><stop offset="1" stop-color="#10090e"/></linearGradient></defs><path d="M 607 141 L 591 151 L 564 189 L 397 474 L 269 718 L 256 749 L 255 778 L 293 863 L 404 1071 L 541 1306 L 592 1383 L 619 1393 L 902 1392 L 920 1384 L 944 1351 L 1149 997 L 1253 795 L 1259 771 L 1256 746 L 1098 442 L 971 225 L 916 146 L 906 141 L 864 139 ZM 884 167 L 885 174 L 819 309 L 773 419 L 939 751L 941 776 L 781 1096 L 661 1317 L 628 1365 L 740 1114 L 586 812 L 573 780 L 571 758 L 600 691 L 750 398 L 841 232 Z" fill="url(#metal)" fill-rule="evenodd" stroke="#f32d49" stroke-width="9"/></svg></div><div class="smallbrand">RENAULT</div></div>
  <div class="rightpanel">
   <div class="lanceurs">
     <button class="appcard" onclick="go('waze')"><svg class="appicon" viewBox="0 0 100 100"><path d="M48 10c-23 0-39 16-39 38 0 11 4 22 14 29l-4 11 16-4c4 2 8 2 13 2 24 0 44-18 44-40S72 10 48 10Z" fill="#fff" stroke="#cfcfd2" stroke-width="4"/><circle cx="35" cy="40" r="4" fill="#0d0d0d"/><circle cx="60" cy="40" r="4" fill="#0d0d0d"/><path d="M35 56q15 15 29-2" stroke="#131313" stroke-width="4" fill="none"/><circle cx="37" cy="87" r="7" fill="#fff" stroke="#121212" stroke-width="4"/><circle cx="69" cy="84" r="7" fill="#fff" stroke="#121212" stroke-width="4"/></svg><span class="appname">WAZE</span><span class="appdesc">Navigation en temps réel</span></button>
     <button class="appcard" onclick="go('spotify')"><svg class="appicon" viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="#e91836"/><path d="M24 38q28-11 57 9M27 51q28-8 51 9M30 63q23-5 42 9" fill="none" stroke="#0d0b0d" stroke-width="7" stroke-linecap="round"/></svg><span class="appname">SPOTIFY</span><span class="appdesc">Votre musique, votre route</span></button>
   </div>
   <div class="smallcontrols">
     <button class="ctl" onclick="go('engine')"><span class="icon">◎</span><span class="copy"><small>SON MOTEUR</small><strong id="engineState">ON</strong></span></button>
     <button class="ctl" onclick="go('wifi')"><span class="icon">●</span><span class="copy"><small>WI-FI VOITURE</small><strong id="wifiSmall">UN CONFIGURANT</strong></span></button>
   </div>
  </div>
</section>
<footer class="footer">
 <button class="navbtn" onclick="go('gps')"><svg class="navicon" viewBox="0 0 24 24"><path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="2.7"/></svg><span>GPS</span></button>
 <button class="navbtn" onclick="go('spotify')"><svg class="navicon" viewBox="0 0 24 24"><path d="M9 18V4l11-2v14"/><circle cx="6" cy="19" r="3"/><circle cx="17" cy="17" r="3"/></svg><span>MUSIQUE</span></button>
 <button class="navbtn" onclick="go('phone')"><svg class="navicon" viewBox="0 0 24 24"><path d="M5 2h5l1 5-3 2c2 4 4 6 8 8l2-3 4 2v5c-1 2-3 2-5 1C8 19 3 14 2 6 2 4 3 2 5 2Z"/></svg><span>TÉLÉPHONE</span></button>
 <button class="navbtn" onclick="go('settings')"><svg class="navicon" viewBox="0 0 24 24"><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M19 5l-2 2M7 17l-2 2"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg><span>PARAMÈTRES</span></button>
 <button class="navbtn" onclick="go('info')"><svg class="navicon" viewBox="0 0 24 24"><path d="M4 10l2-5h12l2 5v9H4zM4 14h16M7 19v3m10-3v3"/><circle cx="7.5" cy="15" r="1"/><circle cx="16.5" cy="15" r="1"/></svg><span>INFOS AUTO</span></button>
</footer></div>
<script>
(fonction(){
let g=document.getElementById('tickMarks');
pour (soit i=0;i<=50;i++){
 const t=(135+i*270/50)*Math.PI/180, major=i%5===0;
 const rr1=major?176:186,rr2=198;
 const x1=250+Math.cos(t)*rr1,y1=250+Math.sin(t)*rr1;
 const x2=250+Math.cos(t)*rr2,y2=250+Math.sin(t)*rr2;
 const ligne=document.createElementNS('http://www.w3.org/2000/svg','ligne');
 ligne.setAttribute('x1',x1);line.setAttribute('y1',y1);line.setAttribute('x2',x2);line.setAttribute('y2',y2);line.setAttribute('stroke',major?'#f4dade':'#6f5d67');line.setAttribute('stroke-width',major?2.5:1);g.appendChild(ligne);
 if(major){const text=document.createElementNS('http://www.w3.org/2000/svg','text');text.setAttribute('x',250+Math.cos(t)*155);text.setAttribute('y',254+Math.sin(t)*155);text.setAttribute('fill','#d4ced2');text.setAttribute('font-size','20');text.setAttribute('text-anchor','middle');text.textContent=i*5;g.appendChild(text);}
}
})();
function go(action){try{if(window.Native&&Native.action){Native.action(action);return;}}catch(e){}console.log('ACTION',action)}
function put(id,value){const el=document.getElementById(id);if(el)el.textContent=String(value)}
fenêtre.driveSetSpeed=function(n){put('speedNum',Math.max(0,Math.round(n)))};
window.driveUpdate=function(s){if(s.clock)put('clock',s.clock);if(s.date)put('date',s.date);if(s.gps){put('gpsState',s.gps);put('gpsDial',s.gps);}if(s.bluetooth)put('btState',s.bluetooth);if(s.wifi){put('wifiState',s.wifi);put('wifiSmall',s.wifi)}if(s.battery)put('batteryState',s.battery);if(s.engine)put('engineState',s.engine);if(typeof s.speed==='number')window.driveSetSpeed(s.speed);};
window.driveUpdate({clock:'--:--',date:'',gps:'EN ATTENTE',bluetooth:'A VERIFIER',wifi:'A CONFIGURATION',battery:'--%',engine:'ON',speed:0});
</script>
</body></html>
""
    }
}