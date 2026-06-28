package com.nedrichards.plexwear.playback

import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexTrack
import junit.framework.TestCase.assertEquals
import org.junit.Test

class PlexMediaItemsTest {
  @Test
  fun directSpec_mapsTrackMetadata() {
    val spec = PlexMediaItems.directSpec(
      credentials = PlexCredentials("https://plex.example.test", "token"),
      track = PlexTrack(
        ratingKey = "42",
        key = "/library/metadata/42",
        title = "Song",
        album = "Album",
        artist = "Artist",
        durationMs = 180000,
        thumb = null,
        partKey = "/library/parts/42/file.mp3",
      ),
    )

    assertEquals("42", spec.mediaId)
    assertEquals("Song", spec.title)
    assertEquals("Artist", spec.artist)
    assertEquals("Album", spec.album)
    assertEquals("https://plex.example.test/library/parts/42/file.mp3?X-Plex-Token=token", spec.uri)
  }
}
