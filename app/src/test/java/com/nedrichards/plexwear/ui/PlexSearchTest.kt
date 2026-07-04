package com.nedrichards.plexwear.ui

import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexAlbum
import com.nedrichards.plexwear.data.PlexPlaylist
import com.nedrichards.plexwear.data.PlexTrack
import junit.framework.TestCase.assertEquals
import org.junit.Test

class PlexSearchTest {
  @Test
  fun filterBrowseItems_matchesAlbumTitleAndArtist() {
    val items = listOf(
      BrowseItem.AlbumItem(PlexAlbum("/album/1", "Blue Train", "John Coltrane", null)),
      BrowseItem.AlbumItem(PlexAlbum("/album/2", "Kind of Blue", "Miles Davis", null)),
    )

    val filtered = filterBrowseItems(items, "coltrane")

    assertEquals(listOf(items[0]), filtered)
  }

  @Test
  fun filterBrowseItems_matchesPlaylistTitle() {
    val items = listOf(
      BrowseItem.PlaylistItem(PlexPlaylist("/playlist/1", "Running", 120_000)),
      BrowseItem.PlaylistItem(PlexPlaylist("/playlist/2", "Quiet evening", 180_000)),
    )

    val filtered = filterBrowseItems(items, "quiet")

    assertEquals(listOf(items[1]), filtered)
  }

  @Test
  fun filterTracks_matchesTitleArtistAndAlbum() {
    val tracks = listOf(
      track("1", "Intro", "Nina Simone", "Pastel Blues"),
      track("2", "Outro", "Bill Evans", "Waltz for Debby"),
    )

    assertEquals(listOf(tracks[0]), filterTracks(tracks, "pastel"))
    assertEquals(listOf(tracks[1]), filterTracks(tracks, "bill"))
    assertEquals(listOf(tracks[0]), filterTracks(tracks, "intro"))
  }

  @Test
  fun blankQueryKeepsOriginalOrder() {
    val tracks = listOf(
      track("1", "One", null, null),
      track("2", "Two", null, null),
    )

    assertEquals(tracks, filterTracks(tracks, " "))
  }

  private fun track(
    ratingKey: String,
    title: String,
    artist: String?,
    album: String?,
  ): PlexTrack = PlexTrack(
    ratingKey = ratingKey,
    key = "/library/metadata/$ratingKey",
    title = title,
    album = album,
    artist = artist,
    durationMs = null,
    thumb = null,
    partKey = null,
    audioCodec = null,
  )
}
