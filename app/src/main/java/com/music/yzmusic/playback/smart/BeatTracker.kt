/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (YZ Music adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into YZ Music -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.yzmusic.playback.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Beat and downbeat tracking with the Beat This! model (CPJKU, ISMIR 2024).
 *
 * Why a model at all: tempo and *meter* are different problems. Autocorrelation reads tempo well,
 * but nothing in an autocorrelation says which of four beats is beat one, and a mix that enters on
 * beat three sounds wrong even when every beat lines up.
 *
 * Beat This! is the one that can be shipped: both its code and its trained weights are MIT. Most
 * published MIR weights, Essentia's included, are CC BY-NC-SA.
 *
 * Everything here is optional. A missing model, an unreadable graph, a rate mismatch, too few
 * peaks, all resolve to null, and the caller keeps whatever it had. A missing beat tracker
 * degrades transitions; a throwing one would break playback.
 */
class BeatTracker(private val context: Context) {

    /** A tracked grid on the analysed audio's own timeline, in seconds. */
    data class Grid(
        val beats: List<Double>,
        val downbeats: List<Double>,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
    )

    @Volatile private var session: OrtSession? = null
    private val lock = Any()

    /** Parsing the graph is far too expensive to repeat per track, so one session is kept. */
    private fun session(): OrtSession? {
        session?.let { return it }
        synchronized(lock) {
            session?.let { return it }
            return runCatching {
                val file = File(context.filesDir, MODEL_ASSET)
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open(MODEL_ASSET).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INFERENCE_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // ORT's arena allocator keeps every block it has ever needed, which for this
                    // graph is tens of megabytes of native heap retained for the life of the
                    // session, far past the model's own size, on a process that also has to
                    // survive in the background. Analysis runs a handful of times per track, so
                    // allocating per run is the right trade.
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                    .also { session = it }
            }.onFailure { Log.w(TAG, "Beat model unavailable; falling back to no grid", it) }
                .getOrNull()
        }
    }

    /**
     * Tracks [pcm], which must already be mono at [MelSpectrogram.sampleRate].
     *
     * [offsetSeconds] is added to every returned time, so a grid tracked on a decoded region maps
     * back onto the full track's timeline rather than starting at zero.
     */
    fun track(pcm: FloatArray, offsetSeconds: Double = 0.0): Grid? {
        val melStarted = System.currentTimeMillis()
        val spectrogram = MelSpectrogram.compute(pcm) ?: return null
        val melMs = System.currentTimeMillis() - melStarted
        val active = session() ?: return null

        val beatLogits = FloatArray(spectrogram.frames)
        val downbeatLogits = FloatArray(spectrogram.frames)
        val inferStarted = System.currentTimeMillis()
        if (!infer(active, spectrogram, beatLogits, downbeatLogits)) return null
        Log.d(
            TAG,
            "mel ${melMs}ms (${spectrogram.frames} frames) " +
                "infer ${System.currentTimeMillis() - inferStarted}ms",
        )

        val fps = MelSpectrogram.frameRate
        val beatFrames = pickPeaks(beatLogits)
        val beats = beatFrames.map { it / fps + offsetSeconds }
        if (beats.size < MIN_BEATS) return null

        val bpm = tempoFromBeats(beats)
        if (bpm <= 0) return null

        // Every downbeat is a beat. The two heads are predicted independently, so their peaks can
        // land a frame apart; snapping each downbeat onto the nearest beat keeps the bar grid a
        // strict subset of the beat grid, which is what the planner assumes when it snaps a
        // transition to a downbeat.
        val downbeats = pickPeaks(downbeatLogits)
            .map { it / fps + offsetSeconds }
            .map { time -> beats.minByOrNull { abs(it - time) } ?: beats.first() }
            .distinct()
            .sorted()

        return Grid(
            beats = beats,
            downbeats = downbeats,
            bpm = bpm,
            beatInterval = 60 / bpm,
            firstBeat = beats.first(),
            beatConfidence = gridConfidence(
                beats,
                beatFrames.map { frame -> beatLogits.getOrElse(frame.roundToInt()) { 0f }.toDouble() },
            ),
        )
    }

    /**
     * Runs the model over the spectrogram in chunks, writing logits into the output arrays.
     *
     * The model reads 1500-frame chunks and has no context at their edges, so [BORDER_FRAMES] are
     * discarded from each side and chunks advance by the difference. The first and last chunk keep
     * their outer border, since there is no neighbouring chunk to supply it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun infer(
        session: OrtSession,
        spectrogram: MelSpectrogram.Spectrogram,
        beatLogits: FloatArray,
        downbeatLogits: FloatArray,
    ): Boolean = runCatching {
        val environment = OrtEnvironment.getEnvironment()
        val mels = spectrogram.mels
        val name = session.inputNames.first()
