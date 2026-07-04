package com.nedrichards.plexwear.ui

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexRequestBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
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
  val artworkUrl = remember(credentials.serverUrl, credentials.token, thumbPath) {
    thumbPath
      ?.takeIf { credentials.isConfigured && it.isNotBlank() }
      ?.let { PlexRequestBuilder.artworkUrl(credentials, it, ARTWORK_REQUEST_SIZE_PX) }
  }
  val bitmap by produceState<Bitmap?>(initialValue = artworkUrl?.let(PlexArtworkCache::get), artworkUrl) {
    if (artworkUrl == null) {
      value = null
      return@produceState
    }

    value = PlexArtworkCache.get(artworkUrl) ?: PlexArtworkLoader.load(artworkUrl)
  }

  if (bitmap == null) {
    Box(
      modifier = modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary),
    )
  } else {
    Image(
      bitmap = bitmap!!.asImageBitmap(),
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = modifier
        .size(size)
        .clip(CircleShape),
    )
  }
}

private object PlexArtworkCache {
  private val cache = LruCache<String, Bitmap>(ARTWORK_CACHE_ENTRIES)

  fun get(url: String): Bitmap? = synchronized(cache) {
    cache.get(url)
  }

  fun put(url: String, bitmap: Bitmap): Bitmap = synchronized(cache) {
    cache.put(url, bitmap)
    bitmap
  }
}

private object PlexArtworkLoader {
  private val requestSemaphore = Semaphore(ARTWORK_CONCURRENT_REQUESTS)

  suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
    PlexArtworkCache.get(url)?.let { return@withContext it }

    requestSemaphore.withPermit {
      PlexArtworkCache.get(url)?.let { return@withPermit it }

      val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = ARTWORK_CONNECT_TIMEOUT_MS
        readTimeout = ARTWORK_READ_TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("Accept", "image/*")
      }

      try {
        if (connection.responseCode !in 200..299) return@withPermit null
        val bytes = connection.inputStream.use { it.readBounded(MAX_ARTWORK_BYTES) } ?: return@withPermit null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { PlexArtworkCache.put(url, it) }
      } catch (_: IOException) {
        null
      } finally {
        connection.disconnect()
      }
    }
  }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(8 * 1024)
  var total = 0

  while (true) {
    val read = read(buffer)
    if (read == -1) break
    total += read
    if (total > maxBytes) return null
    output.write(buffer, 0, read)
  }

  return output.toByteArray()
}

private const val ARTWORK_REQUEST_SIZE_PX = 96
private const val ARTWORK_CACHE_ENTRIES = 48
private const val ARTWORK_CONCURRENT_REQUESTS = 3
private const val ARTWORK_CONNECT_TIMEOUT_MS = 2_000
private const val ARTWORK_READ_TIMEOUT_MS = 4_000
private const val MAX_ARTWORK_BYTES = 512 * 1024
