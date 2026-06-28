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

    assertTrue(url.contains("/music/:/transcode/universal/start.m3u8?"))
    assertTrue(url.contains("audioCodec=aac"))
    assertTrue(url.contains("audioBitrate=128"))
    assertTrue(url.contains("directPlay=0"))
  }
}
