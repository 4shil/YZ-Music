/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native STFT
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

#include "vocal_spectrogram.h"

#include <cmath>
#include <complex>
#include <vector>

namespace yzmusic::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

// Unnormalized in-place radix-2 FFT. kVocalSpectrogramFft is a power of two,
// so no generic padding is performed. Private to this file, matching the
