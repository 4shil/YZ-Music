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

package com.music.yzmusic.playback.smart

/**
 * Stored offline analysis for one track, in that track's own timeline seconds.
 *
 * This is the contract between [TrackAnalyzer] and the transition policy.
 * Nothing here is PCM: the policy and planner read these fields and their
 * confidences, and never touch audio. Every field is optional, because the
 * ladder in [assessTransitionTier] is built to degrade on missing evidence
 * rather than to require it; an all-defaults instance is a legitimate input
 * that simply lands on the bottom rung.
 *
 * Phase 1 fills every field from DSP alone (see native/analyzer/), so
 * [beatConfidence] and [vocalActivityMask] are heuristics rather than a
 * trained model's output — the policy already treats them with the same
 * scrutiny it would a model that failed to load.
 */
data class TrackAnalysis(
    /**
     * Blank means "no status was reported", which counts as ready. Any other
     * value must be [STATUS_READY] for the planner to trust the rest of the
     * fields.
     */
    val status: String = "",
    /** Guards against a stale analysis being paired with the wrong track. */
    val trackId: String = "",
    val duration: Double = 0.0,

    val bpm: Double = 0.0,
    /**
     * Seconds per beat. Redundant with [bpm], but the analyzer measures it
     * directly and it survives tempo drift better, so it is preferred
     * wherever both are available.
     */
