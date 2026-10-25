package com.music.bitchord.ui.player

/**
 * Visual download state for a song, shared across Android and Desktop UI.
 */
sealed interface SongDownloadUiState {
    data object Idle : SongDownloadUiState
    data object Queued : SongDownloadUiState
    data class Downloading(val fraction: Float) : SongDownloadUiState
    data object Downloaded : SongDownloadUiState
    data class Failed(val reason: String = "") : SongDownloadUiState
}
