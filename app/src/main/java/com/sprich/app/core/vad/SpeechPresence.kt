package com.sprich.app.core.vad

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.Vad as NativeVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One IME owns this small classifier. All native access is serialized off Main. */
class SpeechPresence(private val assets: AssetManager) {
    private val lock = Any()
    private var native: NativeVad? = null
    private val window = FloatArray(512)
    private var filled = 0
    private var speaking = false
    @Volatile var ready = false
        private set

    suspend fun prepare() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (native == null) native = NativeVad(assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx"),
                sampleRate = 16000, numThreads = 1, provider = "cpu", debug = false,
            ))
            native!!.reset()
            filled = 0
            speaking = false
            ready = true
        }
    }

    /** Called only by the recorder thread; reuses one fixed 32 ms window. */
    fun detect(samples: ShortArray, offset: Int, length: Int): Boolean = synchronized(lock) {
        require(offset >= 0 && length >= 0 && offset <= samples.size - length)
        val model = checkNotNull(native) { "Speech detector is not ready" }
        var anySpeech = false
        for (i in offset until offset + length) {
            window[filled++] = samples[i] / 32768f
            if (filled == window.size) {
                val probability = model.compute(window)
                check(probability.isFinite()) { "Invalid speech probability" }
                // Hysteresis keeps soft word endings without treating steady room noise as speech.
                speaking = probability >= if (speaking) 0.35f else 0.5f
                anySpeech = anySpeech || speaking
                filled = 0
            }
        }
        anySpeech
    }

    /** The old IME can retire only its own native detector. */
    fun release() = synchronized(lock) {
        ready = false
        native?.release()
        native = null
        filled = 0
        speaking = false
        window.fill(0f)
    }
}
