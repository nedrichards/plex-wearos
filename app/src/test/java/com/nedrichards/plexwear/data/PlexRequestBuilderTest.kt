package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class PlexRequestBuilderTest {
  private val credentials = PlexCredentials(
    serverUrl = "https://plex.example.test/",
    token = "secret token",
  )

  @Test
  fun build_addsPlexHeadersAndTokenQuery() {
    val request = PlexRequestBuilder.build(credentials, "/library/sections", mapOf("type" to "10"))

    assertTrue(request.url.startsWith("https://plex.example.test/library/sections?"))
    assertTrue(request.url.contains("type=10"))
    assertTrue(request.url.contains("X-Plex-Token=secret+token"))
    assertEquals("secret token", request.headers["X-Plex-Token"])
    assertEquals("Plex Wear", request.headers["X-Plex-Product"])
  }

  @Test
  fun transcodeUrl_requestsLowBitrateAacStream() {
    val track = PlexTrack(
      ratingKey = "1",
      key = "/library/metadata/1",
      title = "Song",
      album = null,
      artist = null,
      durationMs = null,
      thumb = null,
      partKey = null,
    )

    val url = PlexRequestBuilder.transcodeUrl(credentials, track)

    assertTrue(url.contains("/music/:/transcode/universal/start?"))
    assertTrue(url.contains("protocol=http"))
    assertTrue(url.contains("audioCodec=aac"))
    assertTrue(url.contains("audioBitrate=192"))
    assertTrue(url.contains("directPlay=0"))
    assertTrue(url.contains("X-Plex-Container-Size=1"))
  }

  @Test
  fun artworkUrl_requestsSmallTranscodedImage() {
    val url = PlexRequestBuilder.artworkUrl(credentials, "/library/metadata/42/thumb/1", 96)

    assertTrue(url.startsWith("https://plex.example.test/photo/:/transcode?"))
    assertTrue(url.contains("url=%2Flibrary%2Fmetadata%2F42%2Fthumb%2F1"))
    assertTrue(url.contains("width=96"))
    assertTrue(url.contains("height=96"))
    assertTrue(url.contains("upscale=0"))
    assertTrue(url.contains("X-Plex-Token=secret+token"))
  }
}
