package com.music.bitchord.desktop

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.playback.smart.BeatTracker
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Whether the Beat This! */
class DesktopBeatModelTest {

    @Test
    fun `the beat model loads in this build`() {
        DesktopAnalysisRuntime.ensureStarted()
        if (!DesktopAnalysisRuntime.available) {
            println("analysis library unavailable — skipping")
            return
        }
        val model = runCatching { File(DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET)) }.getOrNull()
        if (model == null || !model.isFile || model.length() == 0L) {
            println("beat model not bundled (downloaded on demand) — skipping")
            return
        }

        val outcome = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(model.absolutePath, options).use { session ->
                session.inputNames.first()
            }
        }
        val error = outcome.exceptionOrNull()
        if (error is UnsatisfiedLinkError ||
            error?.cause is UnsatisfiedLinkError ||
            error?.message?.contains("library", ignoreCase = true) == true ||
            error?.message?.contains("incompatible architecture", ignoreCase = true) == true ||
            error?.message?.contains("cannot open shared object", ignoreCase = true) == true
        ) {
            println("ONNX Runtime native library unavailable on this platform (${System.getProperty("os.name")} ${System.getProperty("os.arch")}): ${error.message} — skipping")
            return
        }
        error?.let { println("beat model load failed: ${it.message}") }
        assertTrue(
            outcome.isSuccess,
            "the beat model would not load, so Automix has no meter: ${error?.message}",
        )
    }
}
