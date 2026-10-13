package com.nedrichards.plexwear.playback

import android.net.Uri
import android.os.Bundle
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
        primary = mediaItem(spec.primary, track, spec.fallbacks.map { it.uri }, credentials),
        fallbacks = spec.fallbacks.map { mediaItem(it) },
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
        fallbacks = playbackPlanSpec(credentials, track, quality).let { listOf(it.primary) + it.fallbacks },
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
    mediaItemSpec(track, PlexRequestBuilder.streamingUrl(credentials, track), artworkUrl(credentials, track))

  fun transcodeSpec(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality = OfflineQuality.Default,
  ): PlexMediaItemSpec =
    mediaItemSpec(track, PlexRequestBuilder.transcodeUrl(credentials, track, quality.bitrateKbps), artworkUrl(credentials, track))

  private fun artworkUrl(credentials: PlexCredentials, track: PlexTrack): String? =
    track.thumb?.let { thumb ->
      if (thumb.startsWith("file:")) thumb
      else PlexRequestBuilder.artworkUrl(credentials, thumb, 256)
    }

  private fun mediaItemSpec(track: PlexTrack, url: String, artwork: String? = null): PlexMediaItemSpec =
    PlexMediaItemSpec(
      mediaId = track.ratingKey,
      uri = url,
      title = track.title,
      artist = track.artist,
      album = track.album,
      artworkUri = artwork,
    )

  private fun mediaItem(spec: PlexMediaItemSpec, track: PlexTrack? = null, fallbacks: List<String> = emptyList(),
    credentials: PlexCredentials? = null): MediaItem =
    MediaItem.Builder()
      .setMediaId(spec.mediaId)
      .setUri(spec.uri)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(spec.title)
          .setArtist(spec.artist)
          .setAlbumTitle(spec.album)
          .setArtworkUri(spec.artworkUri?.let(Uri::parse))
          .setExtras(Bundle().apply {
            putStringArrayList(FALLBACK_URLS, ArrayList(fallbacks))
            putString(ROUTE_SCOPE, credentials?.cacheScope)
            putStringArrayList(ROUTE_BASE_URLS, ArrayList(credentials?.serverUrls.orEmpty()))
            putString(TRACK_KEY, track?.key)
            putString(TRACK_PART, track?.partKey)
            putString(TRACK_CODEC, track?.audioCodec)
            putLong(TRACK_DURATION, track?.durationMs ?: 0)
          })
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

internal const val FALLBACK_URLS = "plex.fallbackUrls"
internal const val TRACK_KEY = "plex.trackKey"
internal const val TRACK_PART = "plex.trackPart"
internal const val TRACK_CODEC = "plex.trackCodec"
internal const val TRACK_DURATION = "plex.trackDuration"
internal const val ROUTE_SCOPE = "plex.routeScope"
internal const val ROUTE_BASE_URLS = "plex.routeBaseUrls"

/** Match at a path boundary so servers sharing a host or URL prefix stay distinct. */
internal fun sourceServerUrl(source: String, serverUrls: List<String>): String? =
  serverUrls.sortedByDescending { it.length }.firstOrNull { base ->
    source.startsWith(base.trimEnd('/') + "/")
  }

/** Move an available route forward without replenishing any consumed fallback. */
internal fun promotedPlaybackSources(primary: String, fallbacks: List<String>, orderedServers: List<String>): List<String> {
  val sources = listOf(primary) + fallbacks
  if (primary.startsWith("file:") || primary.startsWith("content:")) return sources
  val preferred = orderedServers.firstOrNull { server -> sources.any { sourceServerUrl(it, listOf(server)) != null } }
    ?: return sources
  val index = sources.indexOfFirst { sourceServerUrl(it, listOf(preferred)) != null }
  return if (index <= 0) sources else listOf(sources[index]) + sources.filterIndexed { i, _ -> i != index }
}

internal fun MediaItem.asPlexTrack(): PlexTrack = PlexTrack(
  ratingKey = mediaId,
  key = mediaMetadata.extras?.getString(TRACK_KEY) ?: "/library/metadata/$mediaId",
  title = mediaMetadata.title?.toString().orEmpty(),
  album = mediaMetadata.albumTitle?.toString(),
  artist = mediaMetadata.artist?.toString(),
  durationMs = mediaMetadata.extras?.getLong(TRACK_DURATION)?.takeIf { it > 0 },
  thumb = mediaMetadata.artworkUri?.toString(),
  partKey = mediaMetadata.extras?.getString(TRACK_PART),
  audioCodec = mediaMetadata.extras?.getString(TRACK_CODEC),
)
