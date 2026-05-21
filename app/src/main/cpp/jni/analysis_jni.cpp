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

// JNI bridge to the whole-track DSP analyzer and its resampler.
//
// AnalysisResult carries about twenty fields including strings, which is more
// than is worth marshalling field by field through JNI. It is serialized to
// JSON instead: analysis runs once per track, so the cost is irrelevant
// beside the decode around it, and a string is far easier to log and to test
// against than a hand-packed buffer.
//
// Only the subset the transition policy actually reads is emitted. Chroma,
// the mid and high energy curves, loudness, peak and dynamic range are
// computed by the analyzer but nothing downstream consumes them, and emitting
// them would mean three more float arrays per track for no reader.

#include <jni.h>

#include <string>
#include <vector>

#include "analyzer/audio_analysis.h"
#include "analyzer/resampler.h"

namespace {

// The analyzer's strings are its own literals -- key names like "C# minor"
// and candidate types like "main_drop" -- so they are known ASCII with
// nothing to escape. Anything unexpected is dropped rather than emitted
// unescaped.
void AppendString(std::string& out, const std::string& value) {
  out += '"';
  for (const char character : value) {
    if (character >= 32 && character < 127 && character != '"' && character != '\\') {
      out += character;
    }
  }
  out += '"';
}

void AppendNumber(std::string& out, double value) {
  // Not finite means the field never got a defensible value; null reads as
  // absent on the Kotlin side, which is what every consumer already handles.
  if (!(value == value) || value > 1e308 || value < -1e308) {
    out += "null";
    return;
  }
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%.6g", value);
  out += buffer;
}

void AppendDoubles(std::string& out, const std::vector<double>& values) {
  out += '[';
  for (size_t index = 0; index < values.size(); ++index) {
    if (index > 0) out += ',';
    AppendNumber(out, values[index]);
  }
