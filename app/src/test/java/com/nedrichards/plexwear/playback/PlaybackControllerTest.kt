package com.nedrichards.plexwear.playback

import androidx.media3.common.Player
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class PlaybackControllerTest {
  @Test
  fun startupSucceedsOnlyWhenMedia3ReportsReadyWithoutAnError() {
    assertTrue(playbackStartupSucceeded(Player.STATE_READY, hasPlayerError = false))
    assertFalse(playbackStartupSucceeded(Player.STATE_BUFFERING, hasPlayerError = false))
    assertFalse(playbackStartupSucceeded(Player.STATE_READY, hasPlayerError = true))
  }
}
