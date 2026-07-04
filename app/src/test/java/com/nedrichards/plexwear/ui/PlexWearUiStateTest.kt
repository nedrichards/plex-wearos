package com.nedrichards.plexwear.ui

import com.nedrichards.plexwear.data.PlexTrack
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class PlexWearUiStateTest {
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
