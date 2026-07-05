package com.nedrichards.plexwear.offline

import android.content.Context
import android.net.Uri
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.PlexRequestBuilder
import com.nedrichards.plexwear.data.PlexTrack
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OfflineCacheEntry(
  val ratingKey: String,
  val quality: OfflineQuality,
  val uri: Uri,
  val sizeBytes: Long,
)

data class OfflineCacheSnapshot(
  val entries: List<OfflineCacheEntry> = emptyList(),
  val downloadingTrackIds: Set<String> = emptySet(),
) {
  val downloadedTrackIds: Set<String> = entries.mapTo(mutableSetOf()) { it.ratingKey }
  val totalBytes: Long = entries.sumOf { it.sizeBytes }

  fun qualityForTrack(ratingKey: String): OfflineQuality? =
    entries.firstOrNull { it.ratingKey == ratingKey }?.quality
}

class OfflineCacheManager(context: Context) {
  private val cacheDirectory = File(context.filesDir, CACHE_DIRECTORY_NAME)
  private val downloadMutex = Mutex()
  private val activeDownloadKeys = mutableSetOf<String>()
  private val _snapshot = MutableStateFlow(OfflineCacheSnapshot())

  val snapshot: StateFlow<OfflineCacheSnapshot> = _snapshot

  init {
    refreshSnapshot()
  }

  fun cachedUri(track: PlexTrack, preferredQuality: OfflineQuality): String? =
    (cachedEntry(track.ratingKey, preferredQuality) ?: cachedEntries(track.ratingKey).firstOrNull())
      ?.uri
      ?.toString()

  suspend fun downloadTrack(credentials: PlexCredentials, track: PlexTrack, quality: OfflineQuality) {
    val downloadKey = "${track.ratingKey}:${quality.bitrateKbps}"
    downloadMutex.withLock {
      if (activeDownloadKeys.contains(downloadKey)) return
      if (cacheFile(track.ratingKey, quality).isFile) {
        refreshSnapshot()
        return
      }
      activeDownloadKeys += downloadKey
      refreshSnapshotLocked()
    }

    try {
      writeTrack(credentials, track, quality)
    } finally {
      downloadMutex.withLock {
        activeDownloadKeys -= downloadKey
        refreshSnapshotLocked()
      }
    }
  }

  suspend fun clear() {
    withContext(Dispatchers.IO) {
      cacheDirectory.listFiles()?.forEach { file -> file.deleteRecursively() }
    }
    downloadMutex.withLock {
      refreshSnapshotLocked()
    }
  }

  private suspend fun writeTrack(credentials: PlexCredentials, track: PlexTrack, quality: OfflineQuality) {
    withContext(Dispatchers.IO) {
      cacheDirectory.mkdirs()
      val destination = cacheFile(track.ratingKey, quality)
      val temporary = File(cacheDirectory, "${destination.name}.tmp")
      var lastFailure: Throwable? = null
      credentials.serverUrls.forEach { serverUrl ->
        runCatching {
          downloadTrackToFile(
            credentials = credentials.forServerUrl(serverUrl),
            track = track,
            quality = quality,
            temporary = temporary,
            destination = destination,
          )
        }.onSuccess {
          return@withContext
        }.onFailure { throwable ->
          lastFailure = throwable
        }
      }
      throw lastFailure ?: IllegalStateException("Plex credentials are not configured")
    }
  }

  private fun downloadTrackToFile(
    credentials: PlexCredentials,
    track: PlexTrack,
    quality: OfflineQuality,
    temporary: File,
    destination: File,
  ) {
    val url = PlexRequestBuilder.transcodeUrl(credentials, track, quality.bitrateKbps)
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 10_000
      readTimeout = 60_000
      requestMethod = "GET"
      PlexRequestBuilder.clientHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
      setRequestProperty("X-Plex-Token", credentials.token)
    }

    try {
      val responseCode = connection.responseCode
      if (responseCode !in 200..299) {
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        error("Plex download failed HTTP $responseCode: ${body.take(120)}")
      }
      connection.inputStream.use { input ->
        temporary.outputStream().use { output -> input.copyTo(output) }
      }
      if (temporary.length() == 0L) error("Plex download returned an empty file")
      if (!temporary.renameTo(destination)) {
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not store downloaded track" }
      }
    } finally {
      connection.disconnect()
      temporary.delete()
    }
  }

  private fun refreshSnapshot() {
    _snapshot.value = currentSnapshot()
  }

  private fun refreshSnapshotLocked() {
    _snapshot.value = currentSnapshot()
  }

  private fun currentSnapshot(): OfflineCacheSnapshot =
    OfflineCacheSnapshot(
      entries = cacheDirectory.listFiles()
        ?.filter { it.isFile && it.extension == CACHE_EXTENSION }
        ?.mapNotNull(::entryForFile)
        ?.sortedWith(compareBy<OfflineCacheEntry> { it.ratingKey }.thenBy { it.quality.bitrateKbps })
        .orEmpty(),
      downloadingTrackIds = activeDownloadKeys.mapTo(mutableSetOf()) { it.substringBefore(':') },
    )

  private fun cachedEntry(ratingKey: String, quality: OfflineQuality): OfflineCacheEntry? =
    entryForFile(cacheFile(ratingKey, quality)).takeIf { cacheFile(ratingKey, quality).isFile }

  private fun cachedEntries(ratingKey: String): List<OfflineCacheEntry> {
    val prefix = "${ratingKey.fileKey()}-"
    return cacheDirectory.listFiles()
      ?.filter { it.isFile && it.name.startsWith(prefix) && it.extension == CACHE_EXTENSION }
      ?.mapNotNull(::entryForFile)
      ?.sortedByDescending { it.quality.bitrateKbps }
      .orEmpty()
  }

  private fun cacheFile(ratingKey: String, quality: OfflineQuality): File =
    File(cacheDirectory, "${ratingKey.fileKey()}-${quality.bitrateKbps}.$CACHE_EXTENSION")

  private fun entryForFile(file: File): OfflineCacheEntry? {
    val separatorIndex = file.nameWithoutExtension.lastIndexOf('-')
    if (separatorIndex <= 0 || separatorIndex == file.nameWithoutExtension.lastIndex) return null
    val ratingKey = file.nameWithoutExtension.substring(0, separatorIndex).ratingKeyFromFileKey() ?: return null
    val quality = OfflineQuality.fromBitrateKbps(
      file.nameWithoutExtension.substring(separatorIndex + 1).toIntOrNull() ?: return null,
    )
    return OfflineCacheEntry(
      ratingKey = ratingKey,
      quality = quality,
      uri = Uri.fromFile(file),
      sizeBytes = file.length(),
    )
  }

  private fun String.fileKey(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

  private fun String.ratingKeyFromFileKey(): String? =
    runCatching { String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8) }.getOrNull()

  private companion object {
    const val CACHE_DIRECTORY_NAME = "offline-media"
    const val CACHE_EXTENSION = "aac"
  }
}
