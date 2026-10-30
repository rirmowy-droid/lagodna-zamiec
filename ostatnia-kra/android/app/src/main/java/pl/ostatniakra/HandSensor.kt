package pl.ostatniakra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Front camera -> MediaPipe Hand Landmarker -> height of the palm in the frame
 * (0 = top, 1 = bottom). About 10 frames a second, enough for a slow volume gesture.
 */
class HandSensor(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onResult: (found: Boolean, x: Float, y: Float) -> Unit,
) {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var landmarker: HandLandmarker? = null
    @Volatile private var stopped = false
    private var lastTs = 0L

    fun start() {
        exec.execute {
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .setDelegate(Delegate.CPU)
                    .build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.45f)
                    .setMinHandPresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.45f)
                    .setResultListener { result, _ ->
                        val hand = result.landmarks().firstOrNull()
                        if (hand == null || hand.size < 18) {
                            onResult(false, 0f, 0f)
                        } else {
                            // palm centre: wrist + index and pinky knuckles
                            val ids = intArrayOf(0, 5, 17)
                            var x = 0f
                            var y = 0f
                            for (i in ids) { x += hand[i].x(); y += hand[i].y() }
                            onResult(true, x / ids.size, y / ids.size)
                        }
                    }
                    .setErrorListener { e -> onError(e.message ?: "błąd rozpoznawania dłoni") }
                    .build()
                landmarker = HandLandmarker.createFromOptions(context, options)
                if (stopped) { landmarker?.close(); landmarker = null; return@execute }
                ContextCompat.getMainExecutor(context).execute { bindCamera() }
            } catch (e: Throwable) {
                onError("nie udało się wczytać rozpoznawania dłoni: ${e.message}")
            }
        }
    }

    private fun bindCamera() {
        if (stopped) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val a = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                a.setAnalyzer(exec) { proxy -> analyze(proxy) }
                analysis = a
                p.unbind(a)
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                onReady()
            } catch (e: Throwable) {
                onError("nie udało się włączyć kamery: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        val lm = landmarker
        val now = SystemClock.uptimeMillis()
        if (lm == null || stopped || now - lastTs < 100) { proxy.close(); return }
        lastTs = now
        try {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            proxy.close()
            val bmp = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            lm.detectAsync(BitmapImageBuilder(bmp).build(), now)
        } catch (e: Throwable) {
            try { proxy.close() } catch (_: Throwable) {}
        }
    }

    fun stop() {
        stopped = true
        val a = analysis
        val p = provider
        ContextCompat.getMainExecutor(context).execute {
            if (a != null && p != null) p.unbind(a)
        }
        exec.execute {
            landmarker?.close()
            landmarker = null
        }
        exec.shutdown()
    }
}
