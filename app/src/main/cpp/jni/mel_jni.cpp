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

// JNI bridge to the mel front end.
//
// Kept as thin as a bridge can be: it copies the sample array in, calls
// ComputeBeatSpectrogram, and hands back a flat float[]. No policy, no
// buffering, no threading -- all of that belongs on the Kotlin side where it
// can be cancelled and tested.

#include <jni.h>

#include <vector>

#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"

extern "C" {

// Converts mono float PCM to the model's rate. Separate from nativeCompute
// because the caller decodes at whatever rate the container carries and only
// then knows what conversion is needed.
JNIEXPORT jfloatArray JNICALL
Java_com_music_yzmusic_playback_smart_MelSpectrogram_nativeResample(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble input_rate,
    jdouble output_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
