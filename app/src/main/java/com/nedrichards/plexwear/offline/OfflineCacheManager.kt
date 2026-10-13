package com.nedrichards.plexwear.offline

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.nedrichards.plexwear.PlexWearApplication
import com.nedrichards.plexwear.auth.PlexCredentials
import com.nedrichards.plexwear.data.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OfflineCacheEntry(
  val ratingKey: String,
  val quality: OfflineQuality,
  val uri: Uri,
  val sizeBytes: Long,
  val track: PlexTrack? = null,
  val collectionTitle: String? = null,
  val pinned: Boolean = true,
)
data class OfflineDownloadJob(val title: String, val state: String, val error: String? = null)
data class OfflineCacheSnapshot(
  val entries: List<OfflineCacheEntry> = emptyList(),
  val downloadingTrackIds: Set<String> = emptySet(),
  val jobs: List<OfflineDownloadJob> = emptyList(),
  val ready: Boolean = false,
  val scope: String? = null,
  val collections: Map<String, List<PlexTrack>> = emptyMap(),
) {
  val downloadedTrackIds = entries.mapTo(mutableSetOf()) { it.ratingKey }
  val totalBytes = entries.distinctBy { it.uri }.sumOf { it.sizeBytes }
  val progressSummary: String? = jobs.count { it.state == "Queued" || it.state == "Downloading" }.takeIf { it > 0 }
    ?.let { "$it remaining · ${entries.distinctBy { e -> e.uri }.size} saved" }
  fun qualityForTrack(ratingKey: String): OfflineQuality? = entries.firstOrNull { it.ratingKey == ratingKey }?.quality
}

/** IO-only mutations, a durable index and one serialized transfer protect Clear from late writes. */
class OfflineCacheManager internal constructor(
  context: Context,
  private val writeOverride: (suspend (PlexCredentials, PlexTrack, OfflineQuality, File) -> Unit)?,
) {
  constructor(context: Context) : this(context, null)
  private val context = context.applicationContext
  private val root = File(context.filesDir, "offline-media-v2")
  private val mutex = Mutex()
  private val transitionMutex = Mutex()
  private val transferMutex = Mutex()
  private var credentials: PlexCredentials? = null
  private var records = mutableMapOf<String, DownloadRecord>()
  private var collections = mutableMapOf<String, CollectionJob>()
  private var transfer: Job? = null
  internal var workSliceMs: Long = 4 * 60_000L
  internal var collectionPageLoader: suspend (PlexCredentials, String, Int) -> List<PlexTrack> = { credentials, path, offset ->
    val body = PlexApi().get(credentials, path, mapOf("X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to "50"))
    PlexXmlParser.tracks(body)
  }
  private val _snapshot = MutableStateFlow(OfflineCacheSnapshot())
  val snapshot: StateFlow<OfflineCacheSnapshot> = _snapshot
  private fun directory(scope: String) = File(root, scope)
  private fun catalogue(scope: String) = OfflineCatalogue(directory(scope))
  private fun media(scope: String, record: DownloadRecord) = File(directory(scope), "${record.id}.aac")

  suspend fun activate(next: PlexCredentials) = transitionMutex.withLock { activateInternal(next) }

  private suspend fun activateInternal(next: PlexCredentials) = withContext(Dispatchers.IO) {
    if (credentials?.cacheScope == next.cacheScope && _snapshot.value.ready) { credentials = next; return@withContext }
    stopTransfer()
    mutex.withLock {
      credentials = next
      _snapshot.value = OfflineCacheSnapshot(scope = next.cacheScope)
      // Old unscoped cache cannot be assigned safely to an account/server. Reclaim it without importing IDs.
      val legacy = File(context.filesDir, "offline-media")
      check(!legacy.exists() || legacy.deleteRecursively()) { "Could not remove obsolete offline cache" }
      records = catalogue(next.cacheScope).load()
      records.entries.toList().forEach { (id, record) ->
        val file = media(next.cacheScope, record)
        val reconciled = when {
          record.state == "Complete" && (!file.isFile || file.length() == 0L) -> record.copy(state = "Failed", bytes = 0, error = "Downloaded file is missing. Retry to restore it.")
          record.state == "Downloading" -> record.copy(state = "Queued", bytes = 0)
          else -> record
        }
        records[id] = reconciled
        catalogue(next.cacheScope).save(reconciled)
      }
      collections = loadCollections(next.cacheScope)
      publish()
    }
    if (next.isConfigured) {
      if (records.values.any { it.pinned && it.state == "Queued" } || collections.values.any { it.state == "Queued" }) schedule(next.cacheScope, false)
      if (records.values.any { !it.pinned && it.state == "Queued" }) schedule(next.cacheScope, true)
    }
  }

  fun cachedUri(credentials: PlexCredentials, track: PlexTrack, quality: OfflineQuality): String? {
    val state = snapshot.value
    if (!state.ready || state.scope != credentials.cacheScope) return null
    return state.entries.filter { it.ratingKey == track.ratingKey }.sortedByDescending { it.quality == quality }.firstOrNull()?.uri?.toString()
  }

  suspend fun enqueueTracks(credentials: PlexCredentials, tracks: List<PlexTrack>, quality: OfflineQuality, collectionTitle: String? = null) {
    enqueue(credentials, tracks, quality, collectionTitle, true)
  }
  private suspend fun enqueue(credentials: PlexCredentials, tracks: List<PlexTrack>, quality: OfflineQuality, title: String?, pinned: Boolean) = withContext(Dispatchers.IO) {
    activate(credentials)
    mutex.withLock {
      if (this@OfflineCacheManager.credentials?.cacheScope != credentials.cacheScope) return@withLock
      tracks.distinctBy { it.ratingKey }.forEach { track ->
        val proposed = DownloadRecord(track, quality, title, pinned)
        val old = records[proposed.id]
        val merged = if (old == null) proposed else old.copy(track = track, pinned = pinned || old.pinned,
          collections = old.collections + listOfNotNull(title), state = if (old.state == "Complete") "Complete" else "Queued", error = null, timeouts = 0)
        records[merged.id] = merged
        catalogue(credentials.cacheScope).save(merged)
      }
      publish()
    }
    schedule(credentials.cacheScope, !pinned)
  }

  suspend fun enqueueCollection(credentials: PlexCredentials, path: String, title: String, quality: OfflineQuality) = withContext(Dispatchers.IO) {
    activate(credentials)
    mutex.withLock {
      if (this@OfflineCacheManager.credentials?.cacheScope != credentials.cacheScope) return@withLock
      val job = CollectionJob(offlineFileKey("$path:${quality.bitrateKbps}"), path, title, quality)
      collections[job.id] = job
      saveCollection(credentials.cacheScope, job)
      publish()
    }
    schedule(credentials.cacheScope, false)
  }

  suspend fun maybeAutoCache(credentials: PlexCredentials, track: PlexTrack, quality: OfflineQuality) {
    val app = context as PlexWearApplication
    if (app.offlineSettingsStore.autoCacheEnabled.first()) enqueue(credentials, listOf(track), quality, null, false)
  }

  suspend fun cancelDownloads() = withContext(Dispatchers.IO) {
    stopTransfer()
    mutex.withLock {
      val scope = credentials?.cacheScope ?: return@withLock
      records.replaceAll { _, record -> if (record.state == "Queued" || record.state == "Downloading") record.copy(state = "Cancelled") else record }
      records.values.forEach { catalogue(scope).save(it) }
      collections.replaceAll { _, job -> if (job.state == "Queued" || job.state == "Downloading") job.copy(state = "Cancelled") else job }
      collections.values.forEach { saveCollection(scope, it) }
      publish()
    }
  }
  suspend fun retryDownloads() = withContext(Dispatchers.IO) {
    val scope = mutex.withLock {
      val scope = credentials?.cacheScope ?: return@withLock null
      records.replaceAll { _, r -> if (r.state == "Failed" || r.state == "Cancelled") r.copy(state = "Queued", error = null, timeouts = 0) else r }
      records.values.forEach { catalogue(scope).save(it) }
      collections.replaceAll { _, j -> if (j.state == "Failed" || j.state == "Cancelled") j.copy(state = "Queued", error = null, timeouts = 0) else j }
      collections.values.forEach { saveCollection(scope, it) }
      publish(); scope
    }
    if (scope != null) { schedule(scope, false); schedule(scope, true) }
  }
  suspend fun removeTrack(ratingKey: String) = withContext(Dispatchers.IO) {
    stopTransfer()
    mutex.withLock {
      val scope = credentials?.cacheScope ?: return@withLock
      records.values.filter { it.track.ratingKey == ratingKey }.forEach { r ->
        val file = media(scope, r)
        check(!file.exists() || file.delete()) { "Could not remove downloaded track" }
        catalogue(scope).remove(r.id); records.remove(r.id)
      }
      publish()
    }
    credentials?.takeIf { it.isConfigured }?.let { schedule(it.cacheScope, false); schedule(it.cacheScope, true) }
  }
  suspend fun clear() = withContext(Dispatchers.IO) {
    stopTransfer()
    mutex.withLock {
      val scope = credentials?.cacheScope ?: return@withLock
      check(directory(scope).deleteRecursively()) { "Could not clear downloaded music" }
      records.clear(); collections.clear(); publish()
    }
  }
  private suspend fun stopTransfer() {
    credentials?.let { WorkManager.getInstance(context).cancelAllWorkByTag("offline-${it.cacheScope}").result.get() }
    transfer?.cancelAndJoin()
    transferMutex.withLock { }
  }

  private fun schedule(scope: String, automatic: Boolean) {
    val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
      .setInputData(workDataOf("scope" to scope, "automatic" to automatic))
      .setConstraints(Constraints.Builder().setRequiredNetworkType(if (automatic) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(automatic).setRequiresStorageNotLow(true).build())
      .addTag("offline-$scope").build()
    WorkManager.getInstance(context).enqueueUniqueWork("offline-$scope-$automatic", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  internal suspend fun drain(credentials: PlexCredentials, automatic: Boolean) = transferMutex.withLock {
    transfer = currentCoroutineContext()[Job]
    try {
      // Leave room below WorkManager's execution window for cancellation, disk flush and startup.
      val completed = withTimeoutOrNull(workSliceMs) {
        drainBatch(credentials, automatic)
        true
      } ?: false
      currentCoroutineContext().ensureActive()
      if (!completed) {
        val pending = mutex.withLock {
          snapshot.value.scope == credentials.cacheScope &&
            (records.values.any { it.state == "Queued" && it.pinned != automatic } ||
              (!automatic && collections.values.any { it.state == "Queued" }))
        }
        if (pending) schedule(credentials.cacheScope, automatic)
      }
    } finally { transfer = null }
  }

  private suspend fun drainBatch(credentials: PlexCredentials, automatic: Boolean) {
    if (snapshot.value.scope != credentials.cacheScope) return
    if (automatic && !(context as PlexWearApplication).offlineSettingsStore.autoCacheEnabled.first()) return
    if (!automatic) discoverCollections(credentials)
    while (true) {
      currentCoroutineContext().ensureActive()
      val record = mutex.withLock {
        if (snapshot.value.scope != credentials.cacheScope) return@withLock null
        if (automatic && records.values.any { it.pinned && it.state == "Queued" }) return@withLock null
        records.values.firstOrNull { it.state == "Queued" && it.pinned != automatic }?.also { updateRecord(it.copy(state = "Downloading")) }
      } ?: break
      try {
        download(credentials, record)
        mutex.withLock {
          updateRecord((records[record.id] ?: record).copy(state = "Complete", bytes = media(credentials.cacheScope, record).length(), error = null, timeouts = 0))
        }
        record.track.thumb?.let { thumb ->
          try { (context as PlexWearApplication).artworkStore.load(credentials, thumb, 96) }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Cover failure never fails saved audio. */ }
        }
      } catch (cancelled: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
          mutex.withLock {
            if (snapshot.value.scope == credentials.cacheScope && records[record.id]?.state == "Downloading") {
              val latest = records[record.id] ?: record
              val attempts = latest.timeouts + if (cancelled is TimeoutCancellationException) 1 else 0
              updateRecord(latest.copy(state = if (attempts >= MAX_TIMEOUTS) "Failed" else "Queued",
                timeouts = attempts, error = if (attempts >= MAX_TIMEOUTS) TIMEOUT_MESSAGE else null))
            }
          }
        }
        throw cancelled
      } catch (failure: Exception) {
        mutex.withLock { updateRecord((records[record.id] ?: record).copy(state = "Failed", error = failureMessage(failure))) }
      }
    }
    if (!automatic && mutex.withLock { records.values.any { !it.pinned && it.state == "Queued" } }) {
      schedule(credentials.cacheScope, true)
    }
  }

  private suspend fun discoverCollections(credentials: PlexCredentials) {
    while (true) {
      val job = mutex.withLock { collections.values.firstOrNull { it.state == "Queued" } } ?: break
      try {
        var current = job
        while (true) {
          currentCoroutineContext().ensureActive()
          val tracks = collectionPageLoader(credentials, job.path, current.offset)
          mutex.withLock {
            tracks.distinctBy { it.ratingKey }.forEach { t ->
              val proposed = DownloadRecord(t, job.quality, job.title)
              val old = records[proposed.id]
              val merged = old?.copy(pinned = true, collections = old.collections + job.title,
                state = if (old.state == "Complete" || old.state == "Downloading") old.state else "Queued", error = null, timeouts = if (old.state == "Downloading") old.timeouts else 0) ?: proposed
              records[merged.id] = merged
              catalogue(credentials.cacheScope).save(merged)
            }
            current = current.copy(offset = current.offset + tracks.size, tracks = current.tracks + tracks, timeouts = 0, state = if (tracks.size < 50) "Complete" else "Queued")
            collections[job.id] = current; saveCollection(credentials.cacheScope, current); publish()
          }
          if (tracks.size < 50) break
        }
      } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) withContext(NonCancellable + Dispatchers.IO) {
          mutex.withLock {
            if (snapshot.value.scope == credentials.cacheScope) {
              val latest = collections[job.id] ?: job
              val attempts = latest.timeouts + 1
              val deferred = latest.copy(state = if (attempts >= MAX_TIMEOUTS) "Failed" else "Queued",
                timeouts = attempts, error = if (attempts >= MAX_TIMEOUTS) TIMEOUT_MESSAGE else null)
              collections[job.id] = deferred
              saveCollection(credentials.cacheScope, deferred)
              publish()
            }
          }
        }
        throw cancelled
      }
      catch (failure: Exception) {
        mutex.withLock {
          val failed = (collections[job.id] ?: job).copy(state = "Failed", error = failureMessage(failure))
          collections[job.id] = failed; saveCollection(credentials.cacheScope, failed); publish()
        }
      }
    }
  }

  private suspend fun download(credentials: PlexCredentials, record: DownloadRecord) {
    val destination = media(credentials.cacheScope, record)
    val temporary = File(destination.parentFile, "${record.id}.aac.tmp")
    destination.parentFile?.mkdirs()
    writeOverride?.let { writer ->
      try {
        writer(credentials, record.track, record.quality, temporary)
        currentCoroutineContext().ensureActive()
        check(temporary.length() > 0 && temporary.renameTo(destination)) { "Could not store downloaded music" }
      } finally { temporary.delete() }
      return
    }
    var failure: Exception? = null
    for (url in PlexConnectionResolver.orderedCredentials(credentials).serverUrls) {
      try {
        val connection = (URL(PlexRequestBuilder.transcodeUrl(credentials.forServerUrl(url), record.track, record.quality.bitrateKbps)).openConnection() as HttpURLConnection).apply {
          connectTimeout = 10_000; readTimeout = 60_000
          PlexRequestBuilder.clientHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
          setRequestProperty("X-Plex-Token", credentials.token)
        }
        val budget = mutex.withLock { minOf(availableBytes(record), (temporary.parentFile!!.usableSpace - 16L * 1024 * 1024).coerceAtLeast(0)) }
        connection.executeCancellable { http ->
          if (http.responseCode !in 200..299) throw PlexHttpException(http.responseCode)
          http.inputStream.use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var bytes = 0L
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              bytes += count
              check(bytes <= budget) { "Download storage limit reached. Remove music and retry." }
              output.write(buffer, 0, count)
            }
          } }
          check(temporary.length() > 0) { "Plex returned an empty download" }
        }
        currentCoroutineContext().ensureActive()
        check(temporary.renameTo(destination)) { "Could not store downloaded music" }
        PlexConnectionResolver.succeeded(credentials, url)
        return
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        failure = error
        if (error is PlexHttpException && (error.status == 401 || error.status == 403)) throw error
        PlexConnectionResolver.failed(credentials, url)
      } finally { temporary.delete() }
    }
    throw failure ?: IllegalStateException("Plex server is not configured")
  }

  private fun failureMessage(failure: Exception): String = when {
    failure is PlexHttpException && failure.status in listOf(401, 403) -> "Plex authorization expired. Sign in again."
    failure is PlexHttpException -> "Plex returned HTTP ${failure.status}. Retry the download."
    failure is IllegalStateException && failure.message?.startsWith("Download storage limit") == true -> failure.message!!
    failure is IllegalStateException && failure.message?.startsWith("Could not ") == true -> failure.message!!
    else -> "Download failed. Check the connection and retry."
  }

  private fun availableBytes(record: DownloadRecord): Long {
    val budget = if (record.pinned) MANUAL_BUDGET else AUTO_BUDGET
    var used = records.values.filter { it.pinned == record.pinned && it.state == "Complete" }.sumOf { it.bytes }
    if (!record.pinned) {
      val estimate = (record.track.durationMs ?: 300_000) * record.quality.bitrateKbps / 8
      val scope = credentials!!.cacheScope
      for (candidate in records.values.filter { !it.pinned && it.state == "Complete" }.toList()) {
        if (budget - used >= estimate) break
        val file = media(scope, candidate)
        check(!file.exists() || file.delete()) { "Could not evict cached music" }
        used -= candidate.bytes; records.remove(candidate.id); catalogue(scope).remove(candidate.id)
      }
      publish()
    }
    return (budget - used).coerceAtLeast(0)
  }
  private fun updateRecord(record: DownloadRecord) {
    val scope = credentials!!.cacheScope
    records[record.id] = record; catalogue(scope).save(record); publish()
  }
  private fun publish() {
    val scope = credentials?.cacheScope ?: return
    val entries = records.values.filter { it.state == "Complete" }.flatMap { r ->
      (r.collections.ifEmpty { setOf<String?>(null) }).map { title -> OfflineCacheEntry(r.track.ratingKey, r.quality,
        Uri.fromFile(media(scope, r)), r.bytes, r.track, title, r.pinned) }
    }
    val completedIds = entries.mapTo(hashSetOf()) { it.ratingKey }
    val collectionTracks = entries.filter { it.collectionTitle != null }.groupBy { it.collectionTitle!! }
      .mapValues { (_, items) -> items.mapNotNull { it.track }.distinctBy { it.ratingKey } } +
      collections.values.groupBy { it.title }.mapValues { (_, jobs) ->
        jobs.groupBy { it.path }.values.flatMap { versions ->
          val job = versions.maxWith(compareBy<CollectionJob> { it.tracks.size }.thenBy { it.quality.bitrateKbps })
          job.tracks.filter { track -> track.ratingKey in completedIds }
        }
      }.filterValues { it.isNotEmpty() }
    _snapshot.value = OfflineCacheSnapshot(entries, records.values.filter { it.state == "Queued" || it.state == "Downloading" }.mapTo(mutableSetOf()) { it.track.ratingKey },
      records.values.filter { it.state != "Complete" }.map { OfflineDownloadJob(it.track.title, it.state, it.error) } +
        collections.values.filter { it.state != "Complete" }.map { OfflineDownloadJob(it.title, it.state, it.error) }, true, scope, collectionTracks)
  }

  private data class CollectionJob(val id: String, val path: String, val title: String, val quality: OfflineQuality,
    val offset: Int = 0, val state: String = "Queued", val error: String? = null, val tracks: List<PlexTrack> = emptyList(), val timeouts: Int = 0)
  private fun saveCollection(scope: String, job: CollectionJob) {
    val p = Properties().apply {
      setProperty("path", job.path); setProperty("title", job.title); setProperty("quality", job.quality.bitrateKbps.toString())
      setProperty("timeouts", job.timeouts.toString())
      setProperty("trackCount", job.tracks.size.toString())
      job.tracks.forEachIndexed { index, track ->
        setProperty("track.$index", track.ratingKey)
        track.playlistEntryId?.let { setProperty("entry.$index", it) }
      }
      setProperty("offset", job.offset.toString()); setProperty("state", job.state); job.error?.let { setProperty("error", it) }
    }
    val dir = directory(scope).apply { mkdirs() }
    val tmp = File(dir, "${job.id}.collection.tmp")
    tmp.outputStream().use { p.store(it, null) }
    check(tmp.renameTo(File(dir, "${job.id}.collection"))) { "Could not save download collection" }
  }
  private fun loadCollections(scope: String): MutableMap<String, CollectionJob> = directory(scope).listFiles()?.filter { it.extension == "collection" }?.mapNotNull { file ->
    runCatching {
      val p = Properties().apply { file.inputStream().use { load(it) } }
      CollectionJob(file.nameWithoutExtension, p.getProperty("path"), p.getProperty("title"), OfflineQuality.fromBitrateKbps(p.getProperty("quality").toInt()),
        p.getProperty("offset", "0").toInt(), p.getProperty("state", "Queued"), p.getProperty("error"),
        (0 until p.getProperty("trackCount", "0").toInt()).mapNotNull { index ->
          val id = p.getProperty("track.$index") ?: return@mapNotNull null
          records[offlineFileKey("$id:${p.getProperty("quality")}")]?.track?.copy(playlistEntryId = p.getProperty("entry.$index"))
        }, p.getProperty("timeouts", "0").toInt())
    }.getOrNull()
  }?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
  private companion object {
    const val MAX_TIMEOUTS = 3
    const val TIMEOUT_MESSAGE = "Download timed out repeatedly. Retry on a faster connection."
    const val AUTO_BUDGET = 256L * 1024 * 1024
    const val MANUAL_BUDGET = 1024L * 1024 * 1024
  }
}

class OfflineDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val app = applicationContext as PlexWearApplication
    val credentials = app.authStore.credentials.first()
    if (!credentials.isConfigured || credentials.cacheScope != inputData.getString("scope")) return Result.success()
    val snapshot = app.offlineCacheManager.snapshot.value
    if (snapshot.scope != null && snapshot.scope != credentials.cacheScope) return Result.success()
    if (!snapshot.ready) app.offlineCacheManager.activate(credentials)
    app.offlineCacheManager.drain(credentials, inputData.getBoolean("automatic", false))
    return Result.success()
  }
}
