package com.prism.music.playback.sing

import com.prism.music.R
import com.prism.music.tr
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import java.io.File

/**
 * Starts ONNX Runtime with its engine from Sing's download (the 13+ MB library isn't in the APK). The small Java
 * bridge is in the APK, and ONNX Runtime loads it by name; it needs the engine, which Android's linker finds by the
 * engine's name once it's loaded. So the engine is loaded here first, by its full path, and ONNX Runtime starts as
 * usual after that.
 *
 * This has to work the first time: a class that failed to start can't be started again in the same run of Prism.
 */
object OrtLoader {
    @Volatile private var env: OrtEnvironment? = null

    @Synchronized
    fun environment(libraryDir: File): OrtEnvironment {
        env?.let { return it }
        val engine = File(libraryDir, "libonnxruntime.so")
        require(engine.isFile) { tr(R.string.sing_s_engine_isn_t_downloaded) }
        try {
            System.load(engine.absolutePath)
            return OrtEnvironment.getEnvironment().also {
                // ONNX Runtime has its own telemetry (Microsoft's 1DS), which Prism doesn't want: no telemetry, ever.
                // On Android it can't start (no 1DS HTTP client, so nothing was ever sent), but it's switched off
                // outright rather than left to that.
                runCatching { it.setTelemetry(false) }.onFailure { e -> Log.w("PrismSing", "Couldn't switch ONNX Runtime's telemetry off: $e") }
                env = it
            }
        } catch (t: Throwable) {
            Log.w("PrismSing", "ONNX Runtime didn't start: $t")
            throw IllegalStateException(tr(R.string.sing_s_engine_didn_t_start), t)
        }
    }
}
