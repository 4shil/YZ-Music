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

#include "audio_analysis.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <numeric>
#include <string>
#include <utility>
#include <vector>

// Offline DSP implementation: reusable vectors and percentile copies favor
// readable whole-track analysis over real-time allocation constraints.

namespace yzmusic::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

struct EnvelopeResult {
  double window_seconds = 0.25;
  double noise_floor = 0;
  double reference = 0;
  double threshold = 0;
  double audible_start = 0;
  double pickup_confidence = 0;
  double content_end = 0;
  std::vector<double> levels;
};

double Clamp(double value, double minimum, double maximum) {
  return std::max(minimum, std::min(maximum, value));
}

double ToDb(double value) {
  return value > 1e-9 ? 20.0 * std::log10(value) : -70.0;
}

double Percentile(std::vector<double> values, double ratio) {
  if (values.empty()) return 0;
  const size_t index = static_cast<size_t>(Clamp(ratio, 0, 1) * (values.size() - 1));
  std::nth_element(values.begin(), values.begin() + index, values.end());
  return values[index];
}

double Average(const std::vector<double>& values, size_t start, size_t end) {
  start = std::min(start, values.size());
  end = std::min(std::max(start, end), values.size());
  if (start == end) return 0;
  return std::accumulate(values.begin() + start, values.begin() + end, 0.0) / (end - start);
}

// Only a genuinely deep quiet passage followed by a sustained recovery is a
// comeback. Treating every later loud window as a ramp-up pushes ordinary,
// gently varying outros all the way to the file's end.
bool HasMaterialRecovery(
  const std::vector<double>& levels,
  size_t start,
  size_t sustain_windows,
  double reference,
  double quiet_level
) {
  if (reference <= 0 || quiet_level >= reference * 0.38) return false;
  const double threshold = std::max(reference * 0.72, quiet_level * 1.8);
  for (size_t index = start; index + sustain_windows <= levels.size(); ++index) {
    if (Average(levels, index, index + sustain_windows) >= threshold) return true;
  }
  return false;
}

bool HasQuietThenRecovery(
  const std::vector<double>& levels,
  size_t start,
  size_t sustain_windows,
  double reference
) {
  if (reference <= 0) return false;
  bool found_quiet = false;
  for (size_t index = start; index + sustain_windows <= levels.size(); ++index) {
    const double average = Average(levels, index, index + sustain_windows);
    if (average < reference * 0.38) found_quiet = true;
    else if (found_quiet && average >= reference * 0.72) return true;
  }
  return false;
}

// Unnormalized radix-2 Cooley-Tukey FFT. Internal callers must provide a
// non-empty power-of-two frame; the transform intentionally works in place.
void Fft(std::vector<std::complex<double>>& values) {
  const size_t size = values.size();
  for (size_t index = 1, swapped = 0; index < size; ++index) {
    size_t bit = size >> 1;
    for (; swapped & bit; bit >>= 1) swapped ^= bit;
    swapped ^= bit;
    if (index < swapped) std::swap(values[index], values[swapped]);
  }
