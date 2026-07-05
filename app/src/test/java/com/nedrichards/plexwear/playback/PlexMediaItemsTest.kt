package com.nedrichards.plexwear.playback

import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexTrack
import com.nedrichards.plexwear.offline.OfflineQuality
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
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

  @Test
  fun playbackPlan_usesDirectWithTranscodeFallbackForMp3() {
    val plan = PlexMediaItems.playbackPlanSpec(
      credentials = PlexCredentials("https://plex.example.test", "token"),
      track = track(audioCodec = "mp3", partKey = "/library/parts/42/file.mp3"),
    )

    assertEquals("https://plex.example.test/library/parts/42/file.mp3?X-Plex-Token=token", plan.primary.uri)
    assertNotNull(plan.fallback)
  }

  @Test
  fun playbackPlan_usesDirectForLocalFlac() {
    val plan = PlexMediaItems.playbackPlanSpec(
      credentials = PlexCredentials("https://192-168-1-92.example.plex.direct:32400", "token"),
      track = track(audioCodec = "flac", partKey = "/library/parts/42/file.flac"),
    )

    assertEquals("https://192-168-1-92.example.plex.direct:32400/library/parts/42/file.flac?X-Plex-Token=token", plan.primary.uri)
    assertNotNull(plan.fallback)
  }

  @Test
  fun playbackPlan_usesTranscodeWithDirectFallbackForRemoteFlac() {
    val plan = PlexMediaItems.playbackPlanSpec(
      credentials = PlexCredentials("https://plex.example.test", "token"),
      track = track(audioCodec = "flac", partKey = "/library/parts/42/file.flac"),
    )

    assertEquals("https://plex.example.test/music/:/transcode/universal/start", plan.primary.uri.substringBefore("?"))
    assertEquals("https://plex.example.test/library/parts/42/file.flac?X-Plex-Token=token", plan.fallback?.uri)
  }

  @Test
  fun playbackPlan_usesSelectedTranscodeQuality() {
    val plan = PlexMediaItems.playbackPlanSpec(
      credentials = PlexCredentials("https://plex.example.test", "token"),
      track = track(audioCodec = "flac", partKey = "/library/parts/42/file.flac"),
      quality = OfflineQuality.DataSaver,
    )

    assertEquals("96", plan.primary.uri.substringAfter("audioBitrate=").substringBefore("&"))
  }

  @Test
  fun playbackPlan_prefersCachedFileWithRemoteFallback() {
    val plan = PlexMediaItems.playbackPlanSpec(
      credentials = PlexCredentials("https://plex.example.test", "token"),
      track = track(audioCodec = "flac", partKey = "/library/parts/42/file.flac"),
      cachedUri = "file:///data/user/0/com.nedrichards.plexwear/files/offline-media/track.aac",
    )

    assertEquals("file:///data/user/0/com.nedrichards.plexwear/files/offline-media/track.aac", plan.primary.uri)
    assertEquals("https://plex.example.test/music/:/transcode/universal/start", plan.fallback?.uri?.substringBefore("?"))
  }

  @Test
  fun playbackPlan_usesTranscodeWithDirectFallbackForUnknownCodecs() {
    val plan = PlexMediaItems.playbackPlanSpec(
      credentials = PlexCredentials("https://plex.example.test", "token"),
      track = track(audioCodec = null, partKey = "/library/parts/42/file.wav"),
    )

    assertEquals("https://plex.example.test/music/:/transcode/universal/start", plan.primary.uri.substringBefore("?"))
    assertEquals("https://plex.example.test/library/parts/42/file.wav?X-Plex-Token=token", plan.fallback?.uri)
  }

  private fun track(audioCodec: String?, partKey: String?): PlexTrack =
    PlexTrack(
      ratingKey = "42",
      key = "/library/metadata/42",
      title = "Song",
      album = "Album",
      artist = "Artist",
      durationMs = 180000,
      thumb = null,
      partKey = partKey,
      audioCodec = audioCodec,
    )
}
