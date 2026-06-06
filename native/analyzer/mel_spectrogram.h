/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native mel
 * front end this file is adapted from almost unchanged: every constant here
 * is dictated by the Beat This! model's training data, not a preference, so
 * reimplementing it from a description would silently retrain the input
 * distribution the network has never seen.
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

// Log-mel spectrogram front end for the Beat This! beat/downbeat model.
//
// Every constant here is dictated by the trained network, not chosen: the
// model was trained on torchaudio's MelSpectrogram with these exact
// settings, and a front end that differs even in window convention or
// normalization feeds it something it has never seen. They mirror
// `LogMelSpect` in the upstream project (CPJKU/beat_this, MIT) and its C++
// port (mosynthkey/beat_this_cpp, MIT), which is the reference this was
// written against.
//
// `samples` is contiguous mono Float32 PCM that must already be at
// kBeatSpectrogramSampleRate; the caller owns resampling. Calls borrow the
// input only until they return, own all returned storage, and are
// reentrant. The work is O(n log n) and allocates, so it belongs on a
// worker thread.

#pragma once

#include <cstddef>
#include <vector>

