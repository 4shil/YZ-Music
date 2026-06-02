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
