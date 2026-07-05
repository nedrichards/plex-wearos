package com.nedrichards.plexwear.data

data class PlexLibrary(
  val key: String,
  val title: String,
  val type: String,
)

data class PlexAlbum(
  val key: String,
  val title: String,
  val artist: String?,
  val thumb: String?,
)

data class PlexPlaylist(
  val key: String,
  val title: String,
  val durationMs: Long?,
  val thumb: String? = null,
)

data class PlexTrack(
  val ratingKey: String,
  val key: String,
  val title: String,
  val album: String?,
  val artist: String?,
  val durationMs: Long?,
  val thumb: String?,
  val partKey: String?,
  val audioCodec: String? = null,
)

data class PlexSession(
  val sessionKey: String,
  val title: String,
  val subtitle: String?,
  val type: String,
  val playerTitle: String?,
  val playerMachineIdentifier: String,
  val state: String?,
) {
  val controlType: String = when (type.lowercase()) {
    "track" -> "music"
    "episode", "movie", "video" -> "video"
    else -> type.lowercase().ifBlank { "music" }
  }
  val paused: Boolean = state == "paused"
  val canTogglePlayback: Boolean = playerMachineIdentifier.isNotBlank()
  val playbackAction: PlexPlaybackAction = if (paused) PlexPlaybackAction.Play else PlexPlaybackAction.Pause
}

enum class PlexPlaybackAction(val pathSegment: String) {
  Play("play"),
  Pause("pause"),
}

sealed interface BrowseItem {
  data class LibraryItem(val library: PlexLibrary) : BrowseItem
  data class AlbumItem(val album: PlexAlbum) : BrowseItem
  data class PlaylistItem(val playlist: PlexPlaylist) : BrowseItem
  data class TrackItem(val track: PlexTrack) : BrowseItem
}
