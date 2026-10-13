package com.nedrichards.plexwear.offline

import com.nedrichards.plexwear.data.PlexTrack
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** Small durable index. Media paths contain a scope hash, never tokens or raw account identifiers. */
internal class OfflineCatalogue(private val directory: File) {
  fun load(): MutableMap<String, DownloadRecord> {
    directory.mkdirs()
    directory.listFiles()?.filter { it.extension == "tmp" }?.forEach { it.delete() }
    return directory.listFiles()?.filter { it.extension == "properties" }?.mapNotNull { file ->
      runCatching {
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val track = PlexTrack(p.getProperty("id"), p.getProperty("key"), p.getProperty("title"),
          p.getProperty("album"), p.getProperty("artist"), p.getProperty("duration")?.toLongOrNull(),
          p.getProperty("thumb"), p.getProperty("part"), p.getProperty("codec"))
        val record = DownloadRecord(track, OfflineQuality.fromBitrateKbps(p.getProperty("quality").toInt()),
          p.getProperty("collection"), p.getProperty("pinned", "true").toBoolean(),
          p.getProperty("state", "Queued"), p.getProperty("bytes", "0").toLong(), p.getProperty("error"), p.getProperty("collections", p.getProperty("collection", "")).split("\u001f").filter { it.isNotEmpty() }.toSet(), p.getProperty("timeouts", "0").toInt())
        record.id to record
      }.getOrNull()
    }?.toMap()?.toMutableMap() ?: mutableMapOf()
  }

  fun save(record: DownloadRecord) {
    directory.mkdirs()
    val p = Properties()
    fun put(key: String, value: Any?) { if (value != null) p.setProperty(key, value.toString()) }
    val t = record.track
    put("id", t.ratingKey); put("key", t.key); put("title", t.title); put("album", t.album)
    put("artist", t.artist); put("duration", t.durationMs); put("thumb", t.thumb); put("part", t.partKey)
    put("codec", t.audioCodec); put("quality", record.quality.bitrateKbps); put("collection", record.collectionTitle)
    put("timeouts", record.timeouts); put("collections", record.collections.joinToString("\u001f")); put("pinned", record.pinned); put("state", record.state); put("bytes", record.bytes); put("error", record.error)
    val target = File(directory, "${record.id}.properties")
    val tmp = File(directory, "${record.id}.tmp")
    tmp.outputStream().use { p.store(it, null) }
    check(tmp.renameTo(target)) { "Could not save download catalogue" }
  }

  fun remove(id: String) { check(!File(directory, "$id.properties").exists() || File(directory, "$id.properties").delete()) }
}

internal data class DownloadRecord(
  val track: PlexTrack,
  val quality: OfflineQuality,
  val collectionTitle: String? = null,
  val pinned: Boolean = true,
  val state: String = "Queued",
  val bytes: Long = 0,
  val error: String? = null,
  val collections: Set<String> = collectionTitle?.let { setOf(it) } ?: emptySet(),
  val timeouts: Int = 0,
) {
  val id: String get() = offlineFileKey("${track.ratingKey}:${quality.bitrateKbps}")
}

internal fun offlineFileKey(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
