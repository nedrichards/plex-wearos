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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
  val isPlaying: Boolean = false,
  val mediaItemIndex: Int = -1,
  val errorMessage: String? = null,
)

class PlaybackController(private val context: Context) {
  private var controller: MediaController? = null
  private val _state = MutableStateFlow(PlaybackState())
  val state: StateFlow<PlaybackState> = _state.asStateFlow()
  private val playerListener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      _state.value = PlaybackState(
        isPlaying = player.isPlaying,
        mediaItemIndex = player.currentMediaItemIndex,
        errorMessage = player.playerError?.message,
      )
    }
  }

  suspend fun play(plan: PlexPlaybackPlan) {
    play(listOf(plan))
  }

  suspend fun play(plans: List<PlexPlaybackPlan>) {
    if (plans.isEmpty()) return
    val mediaController = controller ?: connect().also { controller = it }
    if (mediaController.prepareAndPlay(plans.map { it.primary })) return

    val maxFallbacks = plans.maxOf { it.fallbacks.size }
    for (fallbackIndex in 0 until maxFallbacks) {
      val fallbackPrepared = mediaController.prepareAndPlay(
        plans.map { it.fallbacks.getOrNull(fallbackIndex) ?: it.primary },
      )
      if (fallbackPrepared) return
    }
    error("Playback could not start")
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
        playbackStartupSucceeded(playbackState, playerError != null)
      }
    } catch (_: TimeoutCancellationException) {
      false
    } finally {
      removeListener(listener)
    }
  }

  private suspend fun connect(): MediaController = withContext(Dispatchers.IO) {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    MediaController.Builder(context, token).buildAsync().get().also { mediaController ->
      mediaController.addListener(playerListener)
      _state.value = PlaybackState(
        isPlaying = mediaController.isPlaying,
        mediaItemIndex = mediaController.currentMediaItemIndex,
        errorMessage = mediaController.playerError?.message,
      )
    }
  }

  fun release() {
    controller?.removeListener(playerListener)
    controller?.release()
    controller = null
    _state.value = PlaybackState()
  }
}

internal fun playbackStartupSucceeded(playbackState: Int, hasPlayerError: Boolean): Boolean =
  playbackState == Player.STATE_READY && !hasPlayerError
