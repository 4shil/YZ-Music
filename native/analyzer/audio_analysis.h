/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native
 * analyzer this file is adapted from almost unchanged: the mix-out budget,
 * phrase detection and cue scoring below were tuned against real material,
 * and reimplementing them from a description would produce different numbers
 * that YZ Music's transition policy is not calibrated for.
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

// Offline whole-track analysis: envelope, transition, tempo, key, spectral,
// and structure features, entirely DSP -- no ML model. `samples` is
// contiguous, non-interleaved mono Float32 PCM. Callers normally supply
// finite normalized amplitudes in [-1, 1], normally at 11,025 Hz, and a
// duration in seconds equal to samples.size() / sample_rate. The
// implementation guards empty/very-low-rate input, but the JNI bridge owns
// stricter validation; it does not scan every sample or reconcile
// inconsistent duration metadata.
//
// Calls borrow the input vector only until they return and produce results
// that own all vector/string storage. Analysis is reentrant because every
// mutable value is call-local. It intentionally allocates and performs O(n)
// work, so it belongs on a worker thread and must never run in a real-time
// audio callback.

#pragma once

#include <string>
#include <vector>

namespace yzmusic::smart {

// Times are seconds, confidence/probability values are nominally in [0, 1],
// and ordered event vectors use playback order unless stated otherwise.
struct EnergyPoint {
  double time = 0;
  // Energy relative to the corresponding whole-track reference, capped at 1.5.
  // `energy_curve` uses RMS; spectral band curves use FFT-band energy.
  double energy = 0;
};

struct Phrase {
  double start = 0;
  double end = 0;
  std::string type;
  double confidence = 0;
};

struct TempoResult {
  double bpm = 0;
  // Seconds per beat; zero means that no defensible tempo was found.
