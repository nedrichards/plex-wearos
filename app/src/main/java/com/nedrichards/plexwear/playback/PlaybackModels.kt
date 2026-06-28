package com.nedrichards.plexwear.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexRequestBuilder
import com.nedrichards.plexwear.data.PlexTrack

data class PlexMediaItemSpec(
  val mediaId: String,
  val uri: String,
  val title: String,
  val artist: String?,
  val album: String?,
  val artworkUri: String?,
)

object PlexMediaItems {
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
}
