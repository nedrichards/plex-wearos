package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val BROWSE_PAGE_SIZE = 50

internal typealias PlexFetch = suspend (
  credentials: PlexCredentials,
  path: String,
  query: Map<String, String>,
  headers: Map<String, String>,
) -> String

class PlexRepository internal constructor(private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val fetch: PlexFetch) {
  constructor(api: PlexApi) : this(fetch = api::get)

  private val cacheMutex = Mutex()
  private val cache = object : LinkedHashMap<CacheKey, CachedResponse>(MAX_CACHED_RESPONSES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedResponse>?): Boolean =
      size > MAX_CACHED_RESPONSES
  }

  private val inFlight = mutableMapOf<CacheKey, CompletableDeferred<Any>>()
  private var generation = 0L

  suspend fun musicLibraries(credentials: PlexCredentials): List<PlexLibrary> =
    cachedParsed(credentials, "/library/sections", parser = PlexXmlParser::libraries)

  suspend fun recentMusic(credentials: PlexCredentials): List<PlexTrack> =
    cachedParsed(
      credentials = credentials,
      path = "/library/recentlyAdded",
      query = mapOf("type" to "10", "X-Plex-Container-Size" to "6"),
      parser = PlexXmlParser::tracks,
    )

  suspend fun albums(
    credentials: PlexCredentials,
    library: PlexLibrary,
    start: Int = 0,
    pageSize: Int = BROWSE_PAGE_SIZE,
  ): List<PlexAlbum> = cachedParsed(
    credentials = credentials,
    path = "/library/sections/${library.key}/albums",
    query = pageQuery(start, pageSize),
    parser = PlexXmlParser::albums,
  )

  suspend fun playlists(
    credentials: PlexCredentials,
    start: Int = 0,
    pageSize: Int = BROWSE_PAGE_SIZE,
  ): List<PlexPlaylist> = cachedParsed(
    credentials = credentials,
    path = "/playlists",
    query = mapOf("playlistType" to "audio") + pageQuery(start, pageSize),
    parser = PlexXmlParser::playlists,
  )

  suspend fun tracksForAlbum(
    credentials: PlexCredentials,
    album: PlexAlbum,
    start: Int = 0,
    pageSize: Int = BROWSE_PAGE_SIZE,
  ): List<PlexTrack> =
    cachedParsed(credentials, album.key, pageQuery(start, pageSize), PlexXmlParser::tracks)

  suspend fun tracksForPlaylist(
    credentials: PlexCredentials,
    playlist: PlexPlaylist,
    start: Int = 0,
    pageSize: Int = BROWSE_PAGE_SIZE,
  ): List<PlexTrack> =
    cachedParsed(credentials, playlist.key, pageQuery(start, pageSize), PlexXmlParser::tracks)

  suspend fun activeSessions(credentials: PlexCredentials): List<PlexSession> {
    val body = fetch(credentials, "/status/sessions", emptyMap(), emptyMap())
    return parse { PlexXmlParser.sessions(body) }
  }

  suspend fun toggleSessionPlayback(credentials: PlexCredentials, session: PlexSession) {
    fetch(
      credentials,
      "/player/playback/${session.playbackAction.pathSegment}",
      mapOf(
        "type" to session.controlType,
        "commandID" to commandIds.incrementAndGet().toString(),
      ),
      mapOf("X-Plex-Target-Client-Identifier" to session.playerMachineIdentifier),
    )
  }

  suspend fun clearCache() {
    cacheMutex.withLock {
      generation++
      cache.clear()
      inFlight.values.forEach { it.cancel() }
      inFlight.clear()
    }
  }

  private suspend fun <T : Any> cachedParsed(
    credentials: PlexCredentials,
    path: String,
    query: Map<String, String> = emptyMap(),
    parser: (String) -> T,
  ): T {
    val key = CacheKey(credentials.cacheScope, path, query)
    var owner = false
    var requestGeneration = 0L
    val pending = cacheMutex.withLock {
      @Suppress("UNCHECKED_CAST")
      cache[key]?.takeIf { now() - it.createdAt < CACHE_TTL_MS }?.let { return it.value as T }
      inFlight[key] ?: CompletableDeferred<Any>().also {
        owner = true
        requestGeneration = generation
        inFlight[key] = it
      }
    }
    if (!owner) {
      @Suppress("UNCHECKED_CAST")
      return pending.await() as T
    }
    try {
      val body = fetch(credentials, path, query, emptyMap())
      val parsed = parse { parser(body) }
      cacheMutex.withLock {
        if (generation == requestGeneration) {
          cache[key] = CachedResponse(parsed, now())
          inFlight.remove(key)
        }
      }
      pending.complete(parsed)
      return parsed
    } catch (failure: Throwable) {
      cacheMutex.withLock { if (inFlight[key] === pending) inFlight.remove(key) }
      pending.completeExceptionally(failure)
      throw failure
    }
  }

  private data class CachedResponse(val value: Any, val createdAt: Long)

  private suspend fun <T> parse(block: () -> T): T =
    withContext(Dispatchers.Default) { block() }

  private fun pageQuery(start: Int, pageSize: Int): Map<String, String> = mapOf(
    "X-Plex-Container-Start" to start.coerceAtLeast(0).toString(),
    "X-Plex-Container-Size" to pageSize.coerceAtLeast(1).toString(),
  )

  private data class CacheKey(
    val credentials: String,
    val path: String,
    val query: Map<String, String>,
  )

  private companion object {
    const val MAX_CACHED_RESPONSES = 24
    const val CACHE_TTL_MS = 60_000L
    val commandIds = AtomicInteger(1)
  }
}
