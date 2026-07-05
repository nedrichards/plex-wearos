package com.nedrichards.plexwear.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

class PlaybackController(private val context: Context) {
  private var controller: MediaController? = null

  suspend fun play(plan: PlexPlaybackPlan) {
    play(listOf(plan))
  }

  suspend fun play(plans: List<PlexPlaybackPlan>) {
    if (plans.isEmpty()) return
    val mediaController = controller ?: connect().also { controller = it }
    val prepared = mediaController.prepareAndPlay(plans.map { it.primary })
    if (!prepared) {
      val maxFallbacks = plans.maxOf { it.fallbacks.size }
      for (fallbackIndex in 0 until maxFallbacks) {
        val fallbackPrepared = mediaController.prepareAndPlay(
          plans.map { it.fallbacks.getOrNull(fallbackIndex) ?: it.primary },
        )
        if (fallbackPrepared) return
      }
    }
  }

  suspend fun resume() {
    val mediaController = controller ?: connect().also { controller = it }
    mediaController.play()
  }

  suspend fun pause() {
    controller?.pause()
  }

  suspend fun skipToNext() {
    controller?.seekToNextMediaItem()
  }

  suspend fun skipToPrevious() {
    controller?.seekToPreviousMediaItem()
  }

  private suspend fun MediaController.prepareAndPlay(items: List<MediaItem>): Boolean {
    setMediaItems(items)
    prepare()
    play()
    return awaitReadyOrError()
  }

  private suspend fun MediaController.awaitReadyOrError(): Boolean {
    val listener = object : Player.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) = Unit
      override fun onPlayerError(error: PlaybackException) = Unit
    }

    addListener(listener)
    return try {
      withTimeout(5_000) {
        while (playbackState != Player.STATE_READY && playerError == null) {
          kotlinx.coroutines.delay(50)
        }
        playbackState == Player.STATE_READY && playerError == null
      }
    } catch (_: TimeoutCancellationException) {
      true
    } finally {
      removeListener(listener)
    }
  }

  private suspend fun connect(): MediaController = withContext(Dispatchers.IO) {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    MediaController.Builder(context, token).buildAsync().get()
  }

  fun release() {
    controller?.release()
    controller = null
  }
}
