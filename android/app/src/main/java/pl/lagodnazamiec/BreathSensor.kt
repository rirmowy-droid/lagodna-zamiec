package pl.lagodnazamiec

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max

/**
 * Listens to the phone's built-in microphone for the low rumble of breath.
 * Uses the VOICE_RECOGNITION source and never touches the audio mode,
 * so music keeps playing in full quality (also over Bluetooth).
 * Reports the level below ~300 Hz in dB, about 20 times a second.
 */
class BreathSensor(
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        thread = Thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096) * 2
                )
            } catch (e: Throwable) {
                onError("nie udało się otworzyć mikrofonu: ${e.message}"); return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                onError("mikrofon jest zajęty przez inną aplikację"); rec.release(); return@Thread
            }
            rec.startRecording()
            val buf = ShortArray(800) // 50 ms
            val alpha = 0.111 // one-pole low-pass at ~300 Hz for 16 kHz
            var lp = 0.0
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val x = buf[i] / 32768.0
                        lp += alpha * (x - lp)
                        sum += lp * lp
                    }
                    onLevel((10 * log10(sum / n + 1e-12)).toFloat())
                }
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
                rec.release()
            }
        }.apply { name = "breath"; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }
}
