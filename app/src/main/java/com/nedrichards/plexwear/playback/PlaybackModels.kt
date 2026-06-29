package com.nedrichards.plexwear.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexRequestBuilder
import com.nedrichards.plexwear.data.PlexTrack
import java.net.URI

data class PlexMediaItemSpec(
  val mediaId: String,
  val uri: String,
  val title: String,
  val artist: String?,
  val album: String?,
  val artworkUri: String?,
)

data class PlexPlaybackPlan(
  val primary: MediaItem,
  val fallback: MediaItem?,
)

data class PlexPlaybackPlanSpec(
  val primary: PlexMediaItemSpec,
  val fallback: PlexMediaItemSpec?,
)

object PlexMediaItems {
  fun playbackPlan(credentials: PlexCredentials, track: PlexTrack): PlexPlaybackPlan =
    playbackPlanSpec(credentials, track).let { spec ->
      PlexPlaybackPlan(
        primary = mediaItem(spec.primary),
        fallback = spec.fallback?.let(::mediaItem),
      )
    }

  fun playbackPlanSpec(credentials: PlexCredentials, track: PlexTrack): PlexPlaybackPlanSpec {
    val direct = directSpec(credentials, track)
    val transcode = transcodeSpec(credentials, track)

    return when {
      track.prefersDirectPlay(credentials) -> PlexPlaybackPlanSpec(primary = direct, fallback = transcode)
      else -> PlexPlaybackPlanSpec(primary = transcode, fallback = direct)
    }
  }

  fun direct(credentials: PlexCredentials, track: PlexTrack): MediaItem =
    mediaItem(directSpec(credentials, track))

  fun transcode(credentials: PlexCredentials, track: PlexTrack): MediaItem =
    mediaItem(transcodeSpec(credentials, track))

  fun directSpec(credentials: PlexCredentials, track: PlexTrack): PlexMediaItemSpec =
    mediaItemSpec(track, PlexRequestBuilder.streamingUrl(credentials, track))

  fun transcodeSpec(credentials: PlexCredentials, track: PlexTrack): PlexMediaItemSpec =
    mediaItemSpec(track, PlexRequestBuilder.transcodeUrl(credentials, track))

  private fun mediaItemSpec(track: PlexTrack, url: String): PlexMediaItemSpec =
    PlexMediaItemSpec(
      mediaId = track.ratingKey,
      uri = url,
      title = track.title,
      artist = track.artist,
      album = track.album,
      artworkUri = track.thumb,
    )

  private fun mediaItem(spec: PlexMediaItemSpec): MediaItem =
    MediaItem.Builder()
      .setMediaId(spec.mediaId)
      .setUri(spec.uri)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(spec.title)
          .setArtist(spec.artist)
          .setAlbumTitle(spec.album)
          .setArtworkUri(spec.artworkUri?.let(Uri::parse))
          .build(),
      )
      .build()

  private fun PlexTrack.prefersDirectPlay(credentials: PlexCredentials): Boolean =
    when (audioCodec?.lowercase()) {
      "aac", "mp3" -> true
      "flac" -> credentials.serverUrl.isLocalNetworkUrl()
      else -> false
    }

  private fun String.isLocalNetworkUrl(): Boolean {
    val host = runCatching { URI(this).host }.getOrNull()?.lowercase().orEmpty()
    if (host == "localhost" || host.endsWith(".local")) return true

    val dottedHost = host.substringBefore(".plex.direct").replace('-', '.')
    return dottedHost == "127.0.0.1" ||
      dottedHost.startsWith("10.") ||
      dottedHost.startsWith("192.168.") ||
      dottedHost.substringBefore('.', missingDelimiterValue = "").toIntOrNull() == 172 &&
      dottedHost.substringAfter('.', missingDelimiterValue = "").substringBefore('.').toIntOrNull() in 16..31
  }
}
