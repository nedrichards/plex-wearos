package com.nedrichards.plexwear.data

import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlexHttpException(val status: Int) : Exception("Plex returned HTTP $status")

/** Disconnect a blocking request as soon as its caller leaves the destination. */
internal suspend fun <T> HttpURLConnection.executeCancellable(block: (HttpURLConnection) -> T): T =
  kotlinx.coroutines.coroutineScope {
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { disconnect() }
      // Structured IO makes cancellation wait for the disconnected writer to close its file.
      // A subsequent download cannot race an orphan Runnable using the same temporary path.
      launch(Dispatchers.IO) {
        try {
          if (continuation.isActive) continuation.resume(block(this@executeCancellable))
        } catch (failure: Exception) {
          if (continuation.isActive) continuation.resumeWithException(failure)
        } finally { disconnect() }
      }
    }
  }
