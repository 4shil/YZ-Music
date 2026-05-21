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

// JNI bridge to the vocal-separation front end.
//
// Unlike the mel front end this one is stereo and linear-frequency, because
// that is what open-unmix was trained on. The layout it produces is bin-major
// rather than frame-major specifically so it matches the model's tensor shape
// [1, 2, bins, frames] with no transpose on the Kotlin side.

#include <jni.h>

#include <vector>

#include "analyzer/vocal_spectrogram.h"

extern "C" {

// Takes the two channels as separate arrays rather than one interleaved one,
// mirroring the planar layout the front end wants and avoiding a deinterleave
// on either side of the boundary.
JNIEXPORT jfloatArray JNICALL
Java_com_music_yzmusic_playback_smart_VocalSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
