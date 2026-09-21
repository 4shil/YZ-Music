package com.music.yzmusic.data.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The party server's wire format, one-for-one.
 *
 * The server speaks camelCase for exactly this reason — it has no other client
 * — so these carry no `@SerialName` and will not need any. If a field ever has
 * to be renamed on one side, rename it on both rather than papering over the
 * difference here.
 */

/**
 * A song as the party knows it.
 *
 * Deliberately *not* Song. What a party shares is which track is playing and
 * where the playhead is; how each device gets the audio — which source answered,
 * at what quality, from the network or from a download — stays that device's own
 * business.
 */
@Serializable
data class PartyTrack(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
)

/** One signed-in device in the party, as every other device sees it. */
@Serializable
data class PartyMember(
    val memberId: String,
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val isHost: Boolean = false,
    /** Whether they are currently holding a socket — not whether they are still in. */
    val connected: Boolean = false,
    val joinedAtMs: Long = 0,
    val lastSeenMs: Long = 0,
)

/**
 * Where the party is, as of a server timestamp.
 *
 * [positionMs] is not a current position. It is the position at [anchorMs], and
 * it only becomes a current position once the reader adds the time since — see
 * [ListenTogether.partyPositionMs].
 */
@Serializable
data class PartyPlayback(
    /**
     * Bumped by the server on every change. A state whose [seq] is not greater
     * than the one already applied is dropped unread.
     */
    val seq: Long = 0,
    val track: PartyTrack? = null,
    /**
     * Bumped only when the queue's *contents* change, and deliberately the only
     * thing about the queue that rides along with the state.
     */
    val queueSeq: Long = 0,
    val queueLength: Int = 0,
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val anchorMs: Long = 0,
    /** The server's own reading of [positionMs] at the instant it sent the frame. */
    val effectivePositionMs: Long = 0,
    val updatedBy: String? = null,
    /** Member who selected this track; stable across pause, play, and seek controls. */
    val startedBy: String? = null,
    /** Snapshot of their name so attribution survives that member leaving. */
    val startedByName: String? = null,
    val updatedAtMs: Long = 0,
)

/**
 * The party's running order, which travels on its own schedule.
 */
@Serializable
data class PartyQueue(
    val seq: Long = 0,
    val index: Int = -1,
    val items: List<PartyTrack> = emptyList(),
)

@Serializable
data class PartySnapshot(
    val code: String = "",
    val createdAtMs: Long = 0,
    val maxMembers: Int = 5,
    val members: List<PartyMember> = emptyList(),
    val playback: PartyPlayback = PartyPlayback(),
    val queue: PartyQueue = PartyQueue(),
    val serverMs: Long = 0,
)

/** The answer to a create or a join: the code, and this device's key to it. */
@Serializable
data class PartyMembership(
    val code: String,
    val token: String,
    val you: PartyMember,
    val party: PartySnapshot,
    val serverMs: Long = 0,
)

@Serializable
internal data class JoinRequest(
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
internal data class ApiError(
    @SerialName("error") val code: String = "",
    val message: String = "",
)
