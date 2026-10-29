package pl.ostatniakra

import android.annotation.SuppressLint
import android.content.ContentValues
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Shows Ostatnia Kra (assets/index.html) full screen and saves recordings to Downloads. */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

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
        web.addJavascriptInterface(Recorder(), "KraNative")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    /** The page streams a finished WAV in base64 chunks: recBegin, recChunk..., recEnd. */
    inner class Recorder {
        private var out: OutputStream? = null
        private var uri: Uri? = null
        private var label = ""

        @JavascriptInterface
        fun recBegin(name: String): Boolean {
            close()
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OstatniaKra")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                    uri = u
                    out = contentResolver.openOutputStream(u)
                    label = "Pobrane/OstatniaKra/$name"
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "OstatniaKra").apply { mkdirs() }
                    val f = File(dir, name)
                    out = FileOutputStream(f)
                    label = f.absolutePath
                }
                out != null
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun recChunk(b64: String) {
            out?.write(Base64.decode(b64, Base64.DEFAULT))
        }

        @JavascriptInterface
        fun recEnd(): String {
            close()
            uri?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(it, done, null, null)
                }
            }
            uri = null
            return label
        }

        private fun close() {
            try { out?.flush(); out?.close() } catch (_: Exception) {}
            out = null
        }
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
