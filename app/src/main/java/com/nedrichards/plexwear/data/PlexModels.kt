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

sealed interface BrowseItem {
  data class LibraryItem(val library: PlexLibrary) : BrowseItem
  data class AlbumItem(val album: PlexAlbum) : BrowseItem
  data class PlaylistItem(val playlist: PlexPlaylist) : BrowseItem
  data class TrackItem(val track: PlexTrack) : BrowseItem
}
