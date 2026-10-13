package com.nedrichards.plexwear.data

import android.content.Context
import android.util.AtomicFile
import com.nedrichards.plexwear.auth.PlexCredentials
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class HomeSnapshot(
  val libraries: List<PlexLibrary> = emptyList(),
  val recent: List<PlexTrack> = emptyList(),
  val pinnedPlaylists: List<PlexPlaylist> = emptyList(),
)

/** A small last-used Home snapshot, not a copy of the Plex library. */
class HomeSnapshotStore(context: Context) {
  private val directory = File(context.filesDir, "home-snapshots")
  private val mutex = Mutex()

  suspend fun read(credentials: PlexCredentials): HomeSnapshot = withContext(Dispatchers.IO) {
    mutex.withLock { readFile(credentials) }
  }

  suspend fun update(credentials: PlexCredentials, transform: (HomeSnapshot) -> HomeSnapshot): HomeSnapshot =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val snapshot = transform(readFile(credentials))
        directory.mkdirs()
        val file = AtomicFile(File(directory, "${credentials.cacheScope}.json"))
        val json = JSONObject()
          .put("libraries", JSONArray().apply { snapshot.libraries.forEach {
            put(JSONObject().put("key", it.key).put("title", it.title).put("type", it.type))
          } })
          .put("recent", JSONArray().apply { snapshot.recent.forEach { put(trackJson(it)) } })
          .put("pins", JSONArray().apply { snapshot.pinnedPlaylists.forEach {
            put(JSONObject().put("key", it.key).put("title", it.title).put("thumb", it.thumb)
              .put("duration", it.durationMs))
          } })
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        snapshot
      }
    }

  private fun readFile(credentials: PlexCredentials): HomeSnapshot {
    return try {
      val json = JSONObject(AtomicFile(File(directory, "${credentials.cacheScope}.json"))
        .openRead().bufferedReader().use { it.readText() })
      HomeSnapshot(
        libraries = json.optJSONArray("libraries").objects().map {
          PlexLibrary(it.getString("key"), it.getString("title"), it.getString("type"))
        },
        recent = json.optJSONArray("recent").objects().map(::trackFromJson),
        pinnedPlaylists = json.optJSONArray("pins").objects().map {
          PlexPlaylist(it.getString("key"), it.getString("title"), it.optionalLong("duration"), it.optionalString("thumb"))
        },
      )
    } catch (_: Exception) { HomeSnapshot() }
  }
}

internal fun trackJson(track: PlexTrack): JSONObject = JSONObject()
  .put("ratingKey", track.ratingKey).put("key", track.key).put("title", track.title)
  .put("album", track.album).put("artist", track.artist).put("duration", track.durationMs)
  .put("thumb", track.thumb).put("partKey", track.partKey).put("codec", track.audioCodec)
  .put("playlistEntryId", track.playlistEntryId)

internal fun trackFromJson(json: JSONObject): PlexTrack = PlexTrack(
  json.getString("ratingKey"), json.getString("key"), json.getString("title"),
  json.optionalString("album"), json.optionalString("artist"), json.optionalLong("duration"),
  json.optionalString("thumb"), json.optionalString("partKey"), json.optionalString("codec"),
  json.optionalString("playlistEntryId"),
)

internal fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
internal fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else optLong(key)
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)
