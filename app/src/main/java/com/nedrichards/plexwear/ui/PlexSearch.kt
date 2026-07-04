package com.nedrichards.plexwear.ui

import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexTrack

fun filterBrowseItems(items: List<BrowseItem>, query: String): List<BrowseItem> {
  val normalizedQuery = query.normalizedSearchQuery()
  if (normalizedQuery.isBlank()) return items
  return items.filter { item ->
    when (item) {
      is BrowseItem.LibraryItem -> listOf(item.library.title, item.library.type)
      is BrowseItem.AlbumItem -> listOf(item.album.title, item.album.artist)
      is BrowseItem.PlaylistItem -> listOf(item.playlist.title)
      is BrowseItem.TrackItem -> listOf(item.track.title, item.track.artist, item.track.album)
    }.matchesSearchQuery(normalizedQuery)
  }
}

fun filterTracks(tracks: List<PlexTrack>, query: String): List<PlexTrack> {
  val normalizedQuery = query.normalizedSearchQuery()
  if (normalizedQuery.isBlank()) return tracks
  return tracks.filter { track ->
    listOf(track.title, track.artist, track.album).matchesSearchQuery(normalizedQuery)
  }
}

private fun String.normalizedSearchQuery(): String = trim().lowercase()

private fun Iterable<String?>.matchesSearchQuery(normalizedQuery: String): Boolean =
  any { value -> value?.lowercase()?.contains(normalizedQuery) == true }
