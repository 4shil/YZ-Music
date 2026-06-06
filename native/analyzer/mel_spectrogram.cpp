/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native mel
 * front end this file is adapted from almost unchanged.
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

#include "mel_spectrogram.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

namespace yzmusic::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

constexpr double kMinHz = 30.0;
constexpr double kMaxHz = 11000.0;
// The model was trained on log1p(1000 * magnitude); the multiplier is what
// spreads quiet detail across the network's useful input range, so it is
// part of the model contract rather than a taste setting.
constexpr double kLogMultiplier = 1000.0;
constexpr double kAmplitudeFloor = 1e-10;

// Unnormalized in-place radix-2 FFT. kBeatSpectrogramFft is a power of two,
// so no generic padding is performed.
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

// Slaney mel scale: linear below 1 kHz, logarithmic above. This is the
// variant torchaudio uses by default, and it is not interchangeable with
// the HTK formula -- picking the wrong one silently shifts every filter.
double HzToMel(double hz) {
  constexpr double f_sp = 200.0 / 3.0;
  constexpr double min_log_hz = 1000.0;
  const double min_log_mel = min_log_hz / f_sp;
  const double logstep = std::log(6.4) / 27.0;
  if (hz >= min_log_hz) return min_log_mel + std::log(hz / min_log_hz) / logstep;
  return hz / f_sp;
}

double MelToHz(double mel) {
  constexpr double f_sp = 200.0 / 3.0;
  constexpr double min_log_hz = 1000.0;
  const double min_log_mel = min_log_hz / f_sp;
  const double logstep = std::log(6.4) / 27.0;
  if (mel >= min_log_mel) return min_log_hz * std::exp(logstep * (mel - min_log_mel));
  return f_sp * mel;
}

// One triangular mel filter, stored as the run of FFT bins it actually
// covers.
//
// Sparse rather than a dense [bins][mels] matrix on purpose: a triangle
// spans a handful of bins, so the dense form is 98% zeros and costs
// 513 x 128 multiplies per frame -- around 800 million for a four-minute
// track, several seconds of pure zero-multiplying. Storing the run makes
// the same work about 1,000 multiplies per frame.
struct MelFilter {
  size_t first_bin = 0;
  std::vector<double> weights;
};

// Triangular filters, deliberately *not* area-normalized: torchaudio's
// `norm=None` default leaves the triangles at unit peak, and the model was
// trained on that. Slaney-normalizing here would scale every band by its
// own width and quietly change the input distribution.
std::vector<MelFilter> MelFilterbank(double sample_rate) {
  const size_t bins = kBeatSpectrogramFft / 2 + 1;
  const double mel_min = HzToMel(kMinHz);
  const double mel_max = HzToMel(kMaxHz);

