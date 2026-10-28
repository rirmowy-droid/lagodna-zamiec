package pl.lagodnazamiec

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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Front camera → MediaPipe Face Landmarker → smile, blink, and "blowing" (puckered lips / puffed cheeks).
 * Runs about 10 times a second, which is plenty for slow gestures and keeps the phone cool.
 */
class FaceSensor(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onResult: (smile: Float, blink: Float, puff: Float, found: Boolean) -> Unit,
) {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var landmarker: FaceLandmarker? = null
    @Volatile private var stopped = false
    private var lastTs = 0L

    fun start() {
        exec.execute {
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .setDelegate(Delegate.CPU)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.4f)
                    .setMinFacePresenceConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .setOutputFaceBlendshapes(true)
                    .setResultListener { result, _ ->
                        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
                        if (shapes == null) {
                            onResult(0f, 0f, 0f, false)
                        } else {
                            fun g(name: String) = shapes.firstOrNull { it.categoryName() == name }?.score() ?: 0f
                            val smile = (g("mouthSmileLeft") + g("mouthSmileRight")) / 2f
                            val blink = (g("eyeBlinkLeft") + g("eyeBlinkRight")) / 2f
                            val puff = maxOf(g("cheekPuff") * 1.3f, g("mouthFunnel"), g("mouthPucker") * 0.85f)
                            onResult(smile, blink, puff, true)
                        }
                    }
                    .setErrorListener { e -> onError(e.message ?: "błąd rozpoznawania twarzy") }
                    .build()
                landmarker = FaceLandmarker.createFromOptions(context, options)
                if (stopped) { landmarker?.close(); landmarker = null; return@execute }
                ContextCompat.getMainExecutor(context).execute { bindCamera() }
            } catch (e: Throwable) {
                onError("nie udało się wczytać rozpoznawania twarzy: ${e.message}")
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

    /** Stops the camera; the model stays loaded only for this instance. */
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

    fun close() = stop()
}
