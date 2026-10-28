package pl.lagodnazamiec

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject

/**
 * Shows the web version of Łagodna Zamieć (assets/index.html) and feeds it
 * camera and microphone readings taken natively. Doing the sensors natively
 * keeps Android in normal media mode, so Bluetooth headphones keep full quality.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private var face: FaceSensor? = null
    private var breath: BreathSensor? = null

    // what the page asked for
    private var wantCam = false
    private var wantMic = false

    // latest readings (written from sensor threads)
    @Volatile private var smile = 0f
    @Volatile private var blink = 0f
    @Volatile private var puff = 0f
    @Volatile private var found = false
    @Volatile private var db = -100f
    @Volatile private var camState = "off"
    @Volatile private var micState = "off"

    private var resumed = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { applySensors() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(0xFF0B1424.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.addJavascriptInterface(Bridge(), "LZNative")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    inner class Bridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun start(mic: Boolean, cam: Boolean) {
            main.post { requestSensors(mic, cam) }
        }

        @JavascriptInterface
        fun stop() {
            main.post { requestSensors(false, false) }
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestSensors(mic: Boolean, cam: Boolean) {
        wantMic = mic
        wantCam = cam
        val need = mutableListOf<String>()
        if (cam && !granted(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (mic && !granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray()) else applySensors()
    }

    private fun applySensors() {
        // camera
        if (wantCam && resumed) {
            if (!granted(Manifest.permission.CAMERA)) {
                camState = "err:brak zgody na kamerę (Ustawienia → Aplikacje → Łagodna Zamieć)"
            } else if (face == null) {
                camState = "loading"
                face = FaceSensor(
                    context = this,
                    owner = this,
                    onReady = { camState = "ok" },
                    onError = { msg -> camState = "err:$msg" },
                    onResult = { s, b, p, f -> smile = s; blink = b; puff = p; found = f }
                ).also { it.start() }
            }
        } else {
            face?.stop(); face = null
            camState = if (wantCam) camState else "off"
            found = false
        }
        // microphone
        if (wantMic && resumed) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                micState = "err:brak zgody na mikrofon (Ustawienia → Aplikacje → Łagodna Zamieć)"
            } else if (breath == null) {
                breath = BreathSensor(
                    onLevel = { level -> db = level; micState = "ok" },
                    onError = { msg -> micState = "err:$msg" }
                ).also { it.start() }
            }
        } else {
            breath?.stop(); breath = null
            micState = if (wantMic) micState else "off"
        }
        pushNow()
    }

    private val pusher = object : Runnable {
        override fun run() = pushNow()
    }

    private fun pushNow() {
        val o = JSONObject()
            .put("cam", camState).put("mic", micState)
            .put("found", found)
            .put("smile", smile.toDouble()).put("blink", blink.toDouble()).put("puff", puff.toDouble())
            .put("db", db.toDouble())
        web.evaluateJavascript("window.__lzSensors && window.__lzSensors($o)", null)
        if (resumed && (wantCam || wantMic)) {
            main.removeCallbacks(pusher)
            main.postDelayed(pusher, 50)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        web.onResume()
        if (wantCam || wantMic) applySensors()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(pusher)
        face?.stop(); face = null
        breath?.stop(); breath = null
        if (wantCam) camState = "loading"
        if (wantMic) micState = "off"
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        face?.close()
        web.destroy()
        super.onDestroy()
    }
}
