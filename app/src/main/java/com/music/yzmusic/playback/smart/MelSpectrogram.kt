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

/**
 * The log-mel front end the Beat This! model expects, backed by the native analyzer.
 *
 * Every constant is dictated by the trained network rather than chosen: it was trained on
 * torchaudio's MelSpectrogram at 22,050 Hz with n_fft 1024, hop 441 (so exactly 50 frames per
 * second), 128 Slaney mel bands from 30 Hz to 11 kHz, and `log1p(1000 * magnitude)`. A front end
 * that differs even in window convention feeds the model something it has never seen, which is why
 * this is a port of the native C++ rather than a reimplementation.
 *
 * [sampleRate] is required, not preferred: audio at any other rate is refused rather than
 * resampled here, because resampling belongs upstream where full-bandwidth samples still exist.
 */
object MelSpectrogram {

    /** True when the native library loaded. Analysis is optional, so this is a fact, not a fault. */
    val available: Boolean = runCatching { System.loadLibrary("yzmusic_analysis") }.isSuccess

    /** Mel bands per frame; the model's input width. */
    val mels: Int by lazy { if (available) nativeMelCount() else 128 }

    /** The only sample rate the front end accepts. */
    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 22_050.0 }

    /** Samples between frame starts; 441 at 22,050 Hz is exactly 20 ms. */
    val hop: Int by lazy { if (available) nativeHop() else 441 }

    /** Frames per second of output, which is what beat times are derived from. */
