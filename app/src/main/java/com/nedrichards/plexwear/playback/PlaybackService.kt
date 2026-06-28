package com.nedrichards.plexwear.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
      .build()
    player = exoPlayer
    session = MediaLibrarySession.Builder(this, exoPlayer, EmptyLibraryCallback()).build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

  override fun onDestroy() {
    session?.release()
    session = null
    player?.release()
    player = null
    super.onDestroy()
  }

  private class EmptyLibraryCallback : MediaLibrarySession.Callback
}
