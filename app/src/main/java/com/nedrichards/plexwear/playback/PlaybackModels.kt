package com.nedrichards.plexwear.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexRequestBuilder
import com.nedrichards.plexwear.data.PlexTrack
import com.nedrichards.plexwear.offline.OfflineQuality
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
  val fallbacks: List<MediaItem> = emptyList(),
) {
  val fallback: MediaItem? = fallbacks.firstOrNull()
}

data class PlexPlaybackPlanSpec(
  val primary: PlexMediaItemSpec,
  val fallbacks: List<PlexMediaItemSpec> = emptyList(),
) {
  val fallback: PlexMediaItemSpec? = fallbacks.firstOrNull()
}

object PlexMediaItems {
  fun playbackPlan(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality = OfflineQuality.Default,
    cachedUri: String? = null,
  ): PlexPlaybackPlan =
    playbackPlanSpec(credentials, track, quality, cachedUri).let { spec ->
      PlexPlaybackPlan(
        primary = mediaItem(spec.primary),
        fallbacks = spec.fallbacks.map(::mediaItem),
      )
    }

  fun playbackPlanSpec(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality = OfflineQuality.Default,
    cachedUri: String? = null,
  ): PlexPlaybackPlanSpec {
    if (cachedUri != null) {
      return PlexPlaybackPlanSpec(
        primary = mediaItemSpec(track, cachedUri),
        fallbacks = listOf(streamingSpec(credentials, track, quality)),
      )
    }

    val localCredentials = credentials.forServerUrl(credentials.serverUrls.first())
    val direct = directSpec(localCredentials, track)
    val transcode = transcodeSpec(localCredentials, track, quality)
    val remoteFallbacks = credentials.serverUrls
      .drop(1)
      .flatMap { serverUrl ->
        val candidateCredentials = credentials.forServerUrl(serverUrl)
        val remoteDirect = directSpec(candidateCredentials, track)
        val remoteTranscode = transcodeSpec(candidateCredentials, track, quality)
        when {
          track.prefersDirectPlay(candidateCredentials) -> listOf(remoteDirect, remoteTranscode)
          else -> listOf(remoteTranscode, remoteDirect)
        }
      }

    return when {
      track.prefersDirectPlay(localCredentials) -> {
        PlexPlaybackPlanSpec(primary = direct, fallbacks = listOf(transcode) + remoteFallbacks)
      }
      else -> {
        PlexPlaybackPlanSpec(primary = transcode, fallbacks = listOf(direct) + remoteFallbacks)
      }
    }
  }

  fun direct(credentials: PlexCredentials, track: PlexTrack): MediaItem =
    mediaItem(directSpec(credentials, track))

  fun transcode(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality = OfflineQuality.Default,
  ): MediaItem =
    mediaItem(transcodeSpec(credentials, track, quality))

  fun directSpec(credentials: PlexCredentials, track: PlexTrack): PlexMediaItemSpec =
    mediaItemSpec(track, PlexRequestBuilder.streamingUrl(credentials, track))

  fun transcodeSpec(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality = OfflineQuality.Default,
  ): PlexMediaItemSpec =
    mediaItemSpec(track, PlexRequestBuilder.transcodeUrl(credentials, track, quality.bitrateKbps))

  private fun streamingSpec(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality,
  ): PlexMediaItemSpec {
    val remotePlan = playbackPlanSpec(credentials, track, quality)
    return remotePlan.primary
  }

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
