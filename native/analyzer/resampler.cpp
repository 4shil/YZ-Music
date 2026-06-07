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

#include "resampler.h"

#include <algorithm>
#include <cmath>

namespace yzmusic::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

// Kernel table entries per unit of input-sample offset. At 512 the spacing
// between entries is ~0.002 of a sample, and linear interpolation across that
// is orders of magnitude below the quantization already in the audio.
constexpr double kKernelResolution = 512.0;

double Sinc(double x) {
  if (std::abs(x) < 1e-12) return 1.0;
  const double scaled = kPi * x;
  return std::sin(scaled) / scaled;
}

// Blackman rather than Hann: the extra term buys roughly 20 dB of stopband
// attenuation for one more cosine per tap, and the stopband is the only reason
// this filter exists.
double Blackman(double position) {
  // `position` runs 0..1 across the whole window.
  return 0.42 - 0.5 * std::cos(2.0 * kPi * position) +
         0.08 * std::cos(4.0 * kPi * position);
}

}  // namespace

std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
