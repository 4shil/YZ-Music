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

// Offline tempo and beat-grid estimation, entirely DSP (autocorrelation plus a
// phase-locked tracking loop) -- no ML model. All spectra, envelopes, scores,
// and event vectors are call-owned allocations; this file has no
// synchronization or real-time guarantees and must run on a worker thread.
//
// This is the confidence a transition policy without a trained beat-tracking
// model has to work with. It is deliberately treated as less trustworthy than
// a model's grid would be -- see MIN_BEATMATCH_CONFIDENCE in
// TransitionPolicy.kt -- but is real evidence, not a guess: BuildStructure and
// the mix-in/out scoring in audio_analysis.cpp both read the resulting
// downbeats to place transitions on the bar.

#include "audio_analysis.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <numeric>
#include <vector>

namespace yzmusic::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

// How much audio the onset envelope covers. The envelope feeds beat
// *tracking* as well as tempo estimation, so it has to reach the end of the
// track: a transition's mix-out anchor sits in the outro, and a grid that
// stops three minutes short of it is extrapolation, not measurement. The cap
// is a memory guard for pathological input, not a tuning constant -- twenty
// minutes at 86 frames per second is under a megabyte of envelope.
constexpr double kMaxEnvelopeSeconds = 1200.0;

// How much of the envelope the autocorrelation tempo search reads. Bounded
// separately because Correlation() is O(n) per lag across ~110 lags, so
// letting it see a whole track would cost seconds; three minutes is ample to
// establish which metrical level a track is on, and the phase-locked grid
// below handles everything local from there.
constexpr double kMaxTempoSearchSeconds = 180.0;

// Upper edge of the band used to find downbeats. Kick drums live here and
// snares essentially do not, which is the entire point -- see the downbeat
// scoring below.
constexpr double kLowBandHz = 150.0;

double Clamp(double value, double minimum, double maximum) {
  return std::max(minimum, std::min(maximum, value));
}

// Unnormalized in-place radix-2 FFT. The fixed 512-sample caller satisfies the
// non-empty power-of-two precondition, so no generic padding is performed here.
void Fft(std::vector<std::complex<double>>& values) {
  const size_t size = values.size();
  for (size_t index = 1, swapped = 0; index < size; ++index) {
    size_t bit = size >> 1;
    for (; swapped & bit; bit >>= 1) swapped ^= bit;
    swapped ^= bit;
    if (index < swapped) std::swap(values[index], values[swapped]);
  }

  for (size_t length = 2; length <= size; length <<= 1) {
    const std::complex<double> root = std::polar(1.0, -2.0 * kPi / length);
    for (size_t start = 0; start < size; start += length) {
      std::complex<double> weight(1, 0);
      for (size_t offset = 0; offset < length / 2; ++offset) {
        const auto even = values[start + offset];
        const auto odd = values[start + offset + length / 2] * weight;
        values[start + offset] = even + odd;
        values[start + offset + length / 2] = even - odd;
        weight *= root;
      }
    }
  }
}

struct OnsetEnvelopes {
  // Positive spectral flux across the whole spectrum: what "a note started"
  // looks like without regard to which instrument played it.
  std::vector<double> full;
  // The same measure restricted to the bass band. Kept separately because
  // deciding *which* beat is beat one is a different question from deciding
  // where the beats are, and the two want different evidence.
  std::vector<double> low;
};

// Normalizes an onset envelope in place: subtract a local mean to suppress
