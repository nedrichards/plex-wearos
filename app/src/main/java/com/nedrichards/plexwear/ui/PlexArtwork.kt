package com.nedrichards.plexwear.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.nedrichards.plexwear.PlexWearApplication
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.auth.sha256
import com.nedrichards.plexwear.data.PlexConnectionResolver
import com.nedrichards.plexwear.data.PlexHttpException
import com.nedrichards.plexwear.data.PlexRequestBuilder
import com.nedrichards.plexwear.data.executeCancellable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Composable
fun PlexArtwork(
  credentials: PlexCredentials,
  thumbPath: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
) {
  val store = (LocalContext.current.applicationContext as PlexWearApplication).artworkStore
  val density = LocalDensity.current
  val requested = with(density) { size.roundToPx().coerceIn(48, 320) }
  val sizePx = listOf(48, 96, 192, 320).first { it >= requested }
  // Keying the state itself clears an old cover immediately when a row is recycled.
  val key = remember(credentials.cacheScope, thumbPath, sizePx) { "${credentials.cacheScope}:$thumbPath:$sizePx" }
  androidx.compose.runtime.key(key) {
    val bitmap by produceState<Bitmap?>(null, key) {
      value = thumbPath?.let { store.load(credentials, it, sizePx) }
    }
    if (bitmap == null) {
      Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer))
    } else {
      Image(bitmap!!.asImageBitmap(), contentDescription,
        modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    }
  }
}

/** Shared covers for lists, downloaded music, and Now Playing. No tokens in disk names. */
class PlexArtworkStore(context: Context) {
  private val directory = File(context.filesDir, "artwork-cache")
  private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
  }
  private val mutex = Mutex()
  private val pending = mutableMapOf<String, CompletableDeferred<Bitmap?>>()
  private val semaphore = Semaphore(3)

  suspend fun load(credentials: PlexCredentials, thumbPath: String, sizePx: Int = 96): Bitmap? = withContext(Dispatchers.IO) {
    val key = "${credentials.cacheScope}:$thumbPath:$sizePx".sha256()
    memory.get(key)?.let { return@withContext it }
    var owner = false
    val result = mutex.withLock {
      pending[key] ?: CompletableDeferred<Bitmap?>().also { owner = true; pending[key] = it }
    }
    if (!owner) return@withContext result.await()
    try {
      val bitmap = semaphore.withPermit {
        memory.get(key) ?: readDisk(key, sizePx)
          ?: listOf(96, 192, 320).filter { it > sizePx }.firstNotNullOfOrNull { larger ->
            readDisk("${credentials.cacheScope}:$thumbPath:$larger".sha256(), sizePx)
          } ?: fetch(credentials, thumbPath, sizePx, key)
      }
      if (bitmap != null) memory.put(key, bitmap)
      result.complete(bitmap)
      bitmap
    } catch (cancelled: CancellationException) {
      result.cancel(cancelled); throw cancelled
    } catch (_: Exception) {
      result.complete(null); null
    } finally { mutex.withLock { pending.remove(key) } }
  }

  private fun readDisk(key: String, sizePx: Int): Bitmap? {
    val file = File(directory, key)
    if (!file.isFile) return null
    val bitmap = decode(file.readBytes(), sizePx)
    if (bitmap == null) file.delete() else file.setLastModified(System.currentTimeMillis())
    return bitmap
  }

  private suspend fun fetch(credentials: PlexCredentials, path: String, size: Int, key: String): Bitmap? {
    if (!credentials.isConfigured) return null
    for (url in PlexConnectionResolver.orderedCredentials(credentials).serverUrls) {
      try {
        val request = PlexRequestBuilder.build(credentials.forServerUrl(url), "/photo/:/transcode",
          mapOf("url" to path, "width" to size.toString(), "height" to size.toString(), "minSize" to "1", "upscale" to "0"))
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
          connectTimeout = 2_000; readTimeout = 4_000
          request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
          setRequestProperty("Accept", "image/*")
        }
        val bytes = connection.executeCancellable { http ->
          if (http.responseCode !in 200..299) throw PlexHttpException(http.responseCode)
          http.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              check(output.size() + count <= 512 * 1024) { "Artwork is too large" }
              output.write(buffer, 0, count)
            }
            output.toByteArray()
          }
        }
        val bitmap = decode(bytes, size) ?: return null
        PlexConnectionResolver.succeeded(credentials, url)
        directory.mkdirs()
        val temporary = File(directory, "$key.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(File(directory, key))) { "Could not store artwork" }
        trimDisk()
        return bitmap
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: PlexHttpException) {
        if (failure.status == 401 || failure.status == 403) return null
        PlexConnectionResolver.failed(credentials, url)
      } catch (_: Exception) { PlexConnectionResolver.failed(credentials, url) }
    }
    return null
  }

  private fun decode(bytes: ByteArray, target: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (options.outWidth / sample > target * 2 || options.outHeight / sample > target * 2) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
  }

  private fun trimDisk() {
    val files = directory.listFiles()?.filter { it.extension != "tmp" }?.sortedBy { it.lastModified() }.orEmpty()
    var total = files.sumOf { it.length() }
    for (file in files) {
      if (total <= 12 * 1024 * 1024) break
      val size = file.length()
      if (file.delete()) total -= size
    }
  }
}
