package com.nedrichards.plexwear.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import android.os.Bundle
import com.nedrichards.plexwear.data.PlexConnectionResolver
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

class PlaybackService : MediaLibraryService() {
  private var player: ExoPlayer? = null
  private var session: MediaLibrarySession? = null

  override fun onCreate() {
    super.onCreate()
    val exoPlayer = ExoPlayer.Builder(this)
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
          .setUsage(C.USAGE_MEDIA)
          .build(),
        true,
      )
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_NETWORK)
      .build()
    exoPlayer.addListener(object : Player.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_READY) return
        val item = exoPlayer.currentMediaItem ?: return
        val scope = item.mediaMetadata.extras?.getString(ROUTE_SCOPE) ?: return
        val server = sourceServerUrl(item.localConfiguration?.uri?.toString().orEmpty(),
          item.mediaMetadata.extras?.getStringArrayList(ROUTE_BASE_URLS).orEmpty()) ?: return
        PlexConnectionResolver.succeeded(scope, server)
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val item = mediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val scope = extras.getString(ROUTE_SCOPE) ?: return
        val primary = item.localConfiguration?.uri?.toString() ?: return
        val alternatives = extras.getStringArrayList(FALLBACK_URLS).orEmpty()
        val sources = promotedPlaybackSources(primary, alternatives,
          PlexConnectionResolver.orderedUrls(scope, extras.getStringArrayList(ROUTE_BASE_URLS).orEmpty()))
        if (sources.first() == primary) return
        val index = exoPlayer.currentMediaItemIndex
        val position = exoPlayer.currentPosition.coerceAtLeast(0)
        val wasPrepared = exoPlayer.playbackState != Player.STATE_IDLE
        val updatedExtras = Bundle(extras).apply {
          putStringArrayList(FALLBACK_URLS, ArrayList(sources.drop(1)))
        }
        exoPlayer.replaceMediaItem(index, item.buildUpon().setUri(sources.first()).setMediaMetadata(
          item.mediaMetadata.buildUpon().setExtras(updatedExtras).build(),
        ).build())
        exoPlayer.seekTo(index, position)
        if (wasPrepared) exoPlayer.prepare()
      }

      override fun onPlayerError(error: PlaybackException) {
        val item = exoPlayer.currentMediaItem ?: return
        val scope = item.mediaMetadata.extras?.getString(ROUTE_SCOPE)
        val server = sourceServerUrl(item.localConfiguration?.uri?.toString().orEmpty(),
          item.mediaMetadata.extras?.getStringArrayList(ROUTE_BASE_URLS).orEmpty())
        if (scope != null && server != null) PlexConnectionResolver.failed(scope, server)
        val alternatives = item.mediaMetadata.extras?.getStringArrayList(FALLBACK_URLS).orEmpty()
        val next = alternatives.firstOrNull() ?: return
        val index = exoPlayer.currentMediaItemIndex
        val position = exoPlayer.currentPosition.coerceAtLeast(0)
        val shouldPlay = exoPlayer.playWhenReady
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
          putStringArrayList(FALLBACK_URLS, ArrayList(alternatives.drop(1)))
        }
        val replacement = item.buildUpon().setUri(next).setMediaMetadata(
          item.mediaMetadata.buildUpon().setExtras(extras).build(),
        ).build()
        // Only replace the failing occurrence; all other queue entries and their retries survive.
        exoPlayer.replaceMediaItem(index, replacement)
        exoPlayer.seekTo(index, position)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = shouldPlay
      }
    })
    player = exoPlayer
    session = MediaLibrarySession.Builder(this, exoPlayer, EmptyLibraryCallback()).build()
  }

  @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

  override fun onDestroy() {
    session?.release()
    session = null
    player?.release()
    player = null
    super.onDestroy()
  }

  private class EmptyLibraryCallback : MediaLibrarySession.Callback
}
