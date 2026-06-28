package com.nedrichards.plexwear.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaybackController(private val context: Context) {
  private var controller: MediaController? = null

  suspend fun play(item: MediaItem) {
    val mediaController = controller ?: connect().also { controller = it }
    mediaController.setMediaItem(item)
    mediaController.prepare()
    mediaController.play()
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
