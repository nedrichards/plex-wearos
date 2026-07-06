package com.nedrichards.plexwear.ui

import com.nedrichards.plexwear.data.BrowseItem
import com.nedrichards.plexwear.data.PlexLibrary
import com.nedrichards.plexwear.data.PlexTrack
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class PlexWearUiStateTest {
  @Test
  fun browseRowSubtitle_describesLibraryNavigationInsteadOfPlexType() {
    val item = BrowseItem.LibraryItem(PlexLibrary("1", "Music", "artist"))

    assertEquals("Music", item.browseRowTitle())
    assertEquals("Browse albums", item.browseRowSubtitle())
  }

  @Test
  fun canOpenCurrentPlayback_requiresNowPlayingTrack() {
    assertFalse(PlexWearUiState(screen = Screen.Home).canOpenCurrentPlayback)

    assertTrue(
      PlexWearUiState(
        screen = Screen.Home,
        nowPlaying = track(),
      ).canOpenCurrentPlayback,
    )
  }

  @Test
  fun canOpenCurrentPlayback_hidesRouteOnNowPlayingScreen() {
    val state = PlexWearUiState(
      screen = Screen.NowPlaying,
      nowPlaying = track(),
    )

    assertFalse(state.canOpenCurrentPlayback)
  }

  @Test
  fun queueControls_reflectCurrentTrackPosition() {
    val tracks = listOf(track("1"), track("2"), track("3"))

    val first = PlexWearUiState(nowPlayingQueue = tracks, nowPlayingIndex = 0)
    val middle = PlexWearUiState(nowPlayingQueue = tracks, nowPlayingIndex = 1)
    val last = PlexWearUiState(nowPlayingQueue = tracks, nowPlayingIndex = 2)

    assertFalse(first.canPlayPrevious)
    assertTrue(first.canPlayNext)
    assertTrue(middle.canPlayPrevious)
    assertTrue(middle.canPlayNext)
    assertTrue(last.canPlayPrevious)
    assertFalse(last.canPlayNext)
  }

  @Test
  fun topLevelActions_keepGlobalNavigationAboveScreenContent() {
    assertEquals(emptyList<TopLevelAction>(), PlexWearUiState(screen = Screen.Home).topLevelActions())

    assertEquals(
      listOf(TopLevelAction.NowPlaying),
      PlexWearUiState(screen = Screen.Home, nowPlaying = track()).topLevelActions(),
    )

    assertEquals(
      listOf(TopLevelAction.Home),
      PlexWearUiState(screen = Screen.NowPlaying, nowPlaying = track()).topLevelActions(),
    )

    assertEquals(
      listOf(TopLevelAction.Home, TopLevelAction.NowPlaying),
      PlexWearUiState(screen = Screen.Tracks, nowPlaying = track()).topLevelActions(),
    )
  }

  private fun track(ratingKey: String = "1"): PlexTrack = PlexTrack(
    ratingKey = ratingKey,
    key = "/library/metadata/$ratingKey",
    title = "Song",
    album = null,
    artist = null,
    durationMs = null,
    thumb = null,
    partKey = null,
    audioCodec = null,
  )
}
