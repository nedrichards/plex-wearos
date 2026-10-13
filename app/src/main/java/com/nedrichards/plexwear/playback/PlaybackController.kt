package com.nedrichards.plexwear.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nedrichards.plexwear.data.PlexTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PlaybackStatus { Idle, Starting, Buffering, Playing, Paused, Ended, Failed }

data class PlaybackState(
  val isPlaying: Boolean = false,
  val playWhenReady: Boolean = false,
  val mediaItemIndex: Int = -1,
  val errorMessage: String? = null,
  val queue: List<PlexTrack> = emptyList(),
  val playbackStatus: PlaybackStatus = PlaybackStatus.Idle,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
)

/** All Media3 operations, including construction and cleanup, run on its application looper. */
class PlaybackController(private val context: Context) {
  private var controller: MediaController? = null
  private val connectionMutex = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var progressJob: Job? = null
  private var released = false
  private val _state = MutableStateFlow(PlaybackState())
  val state: StateFlow<PlaybackState> = _state.asStateFlow()
  private val playerListener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = publish(player)
  }

  private fun publish(player: Player) {
    _state.value = PlaybackState(
      isPlaying = player.isPlaying,
      playWhenReady = player.playWhenReady,
      mediaItemIndex = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
      errorMessage = player.playerError?.let { "Unable to play this track. Try another connection or download." },
      queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).asPlexTrack() },
      playbackStatus = when {
        player.playerError != null -> PlaybackStatus.Failed
        player.playbackState == Player.STATE_ENDED -> PlaybackStatus.Ended
        player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Buffering
        player.isPlaying -> PlaybackStatus.Playing
        player.playbackState == Player.STATE_READY -> PlaybackStatus.Paused
        player.mediaItemCount > 0 && player.playWhenReady -> PlaybackStatus.Starting
        else -> PlaybackStatus.Idle
      },
      positionMs = player.currentPosition.coerceAtLeast(0),
      durationMs = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0)
        ?: player.currentMediaItem?.asPlexTrack()?.durationMs ?: 0,
    )
  }

  suspend fun reconnect() { connect() }
  suspend fun play(plan: PlexPlaybackPlan) = play(listOf(plan))
  suspend fun play(plans: List<PlexPlaybackPlan>) = withContext(Dispatchers.Main.immediate) {
    if (plans.isEmpty()) return@withContext
    val player = connect()
    player.setMediaItems(plans.map { it.primary })
    player.prepare()
    player.play()
    publish(player)
    // Readiness and errors are reported by the session listener; do not block transport
    // actions or restart the entire queue when an individual source needs recovery.
  }
  suspend fun append(plans: List<PlexPlaybackPlan>) = withContext(Dispatchers.Main.immediate) {
    if (plans.isNotEmpty()) connect().addMediaItems(plans.map { it.primary })
  }
  suspend fun resume() = withContext(Dispatchers.Main.immediate) {
    val player = connect()
    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
    if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
    player.play()
  }
  suspend fun pause() = withContext(Dispatchers.Main.immediate) { controller?.pause(); Unit }
  suspend fun skipToNext() = withContext(Dispatchers.Main.immediate) { controller?.seekToNextMediaItem(); Unit }
  suspend fun skipToPrevious() = withContext(Dispatchers.Main.immediate) { controller?.seekToPreviousMediaItem(); Unit }
  suspend fun stop() = withContext(Dispatchers.Main.immediate) {
    val player = connect()
    player.stop()
    player.clearMediaItems()
    publish(player)
  }

  private suspend fun connect(): MediaController = withContext(Dispatchers.Main.immediate) {
    connectionMutex.withLock {
      check(!released) { "Playback controller released" }
      controller?.let { return@withLock it }
      val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
      val future = MediaController.Builder(context, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
      val mainHandler = Handler(Looper.getMainLooper())
      val connected = try {
        withTimeout(15_000) {
          suspendCancellableCoroutine<MediaController> { continuation ->
            continuation.invokeOnCancellation {
              // Cancellation belongs to the caller and may arrive from any dispatcher.
              mainHandler.post { MediaController.releaseFuture(future) }
            }
            future.addListener({
              try {
                val result = future.get()
                if (continuation.isActive) continuation.resume(result)
              } catch (exception: Exception) {
                if (continuation.isActive) continuation.resumeWithException(exception.cause ?: exception)
              }
            }, { runnable -> mainHandler.post(runnable) })
          }
        }
      } catch (exception: Exception) {
        if (exception !is CancellationException) MediaController.releaseFuture(future)
        throw exception
      }
      try {
        check(!released) { "Playback controller released" }
        connected.addListener(playerListener)
        controller = connected
        publish(connected)
        progressJob = scope.launch {
          while (isActive) {
            delay(1_000)
            // Avoid rebuilding the queue on every clock tick.
            _state.value = _state.value.copy(positionMs = connected.currentPosition.coerceAtLeast(0))
          }
        }
        connected
      } catch (exception: Exception) {
        connected.release()
        throw exception
      }
    }
  }

  fun release() {
    released = true
    scope.launch {
      progressJob?.cancel()
      controller?.removeListener(playerListener)
      controller?.release()
      controller = null
      _state.value = PlaybackState()
      scope.cancel()
    }
  }
}

internal fun playbackStartupSucceeded(playbackState: Int, hasPlayerError: Boolean): Boolean =
  playbackState == Player.STATE_READY && !hasPlayerError
