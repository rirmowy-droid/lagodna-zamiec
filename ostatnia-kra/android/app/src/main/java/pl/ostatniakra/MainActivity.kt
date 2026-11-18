package pl.ostatniakra

import android.annotation.SuppressLint
import android.content.ContentValues
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows Ostatnia Kra (assets/index.html) full screen.
 * Native extra for the page: streamed WAV recording (mix and stems) saved to Downloads.
 * (Gesture volume from the front camera is switched off for now; HandSensor.kt and the
 * camera code are in the git history, commit 2941e32, if it comes back.)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(0xFFFFFFFF.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        // a new app version must never show a page cached by the previous one
        val ver = try { packageManager.getPackageInfo(packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } } catch (e: Exception) { 0L }
        val prefs = getSharedPreferences("kra", MODE_PRIVATE)
        if (prefs.getLong("ver", -1L) != ver) { web.clearCache(true); prefs.edit().putLong("ver", ver).apply() }
        web.addJavascriptInterface(Bridge(), "KraNative")
        // Android 15+ draws apps edge to edge: keep the page clear of the status and navigation bars
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html?v=$ver")
    }

    /** Recording: each file streams raw PCM to a temp file, then becomes a WAV in Downloads on close. */
    private class Rec(val name: String, val tmp: File, val out: FileOutputStream) { var bytes = 0L }
    private val recs = ConcurrentHashMap<Int, Rec>()
    private val nextId = AtomicInteger(1)

    inner class Bridge {
        @JavascriptInterface
        fun recOpen(name: String): Int = try {
            val tmp = File.createTempFile("kra", ".pcm", cacheDir)
            val id = nextId.getAndIncrement()
            recs[id] = Rec(name, tmp, FileOutputStream(tmp))
            id
        } catch (e: Exception) { -1 }

        @JavascriptInterface
        fun recWrite(id: Int, b64: String) {
            val r = recs[id] ?: return
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            synchronized(r) { r.out.write(bytes); r.bytes += bytes.size }
        }

        @JavascriptInterface
        fun recClose(id: Int, folder: String, sampleRate: Int): String {
            val r = recs.remove(id) ?: return ""
            synchronized(r) { try { r.out.flush(); r.out.close() } catch (_: Exception) {} }
            return try {
                val label: String
                val os: OutputStream
                var uri: android.net.Uri? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, r.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OstatniaKra/" + folder)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return ""
                    os = contentResolver.openOutputStream(uri) ?: return ""
                    label = "Pobrane/OstatniaKra/$folder/${r.name}"
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "OstatniaKra/$folder").apply { mkdirs() }
                    val f = File(dir, r.name)
                    os = FileOutputStream(f)
                    label = f.absolutePath
                }
                os.use { o ->
                    o.write(wavHeader(r.bytes, sampleRate))
                    FileInputStream(r.tmp).use { it.copyTo(o, 1 shl 16) }
                }
                if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                label
            } catch (e: Exception) {
                ""
            } finally {
                r.tmp.delete()
            }
        }
    }

    private fun wavHeader(dataBytes: Long, sr: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2); b.putInt(sr); b.putInt(sr * 4)
        b.putShort(4); b.putShort(16); b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
