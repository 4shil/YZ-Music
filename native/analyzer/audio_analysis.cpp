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
