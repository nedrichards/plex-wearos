package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource

class PlexApi {
  suspend fun get(
    credentials: PlexCredentials,
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): String =
    withContext(Dispatchers.IO) {
      var lastFailure: Throwable? = null
      credentials.serverUrls.forEach { serverUrl ->
        runCatching {
          get(PlexRequestBuilder.build(credentials.forServerUrl(serverUrl), path, query, headers))
        }.onSuccess { body ->
          return@withContext body
        }.onFailure { throwable ->
          lastFailure = throwable
        }
      }
      throw lastFailure ?: IllegalStateException("Plex credentials are not configured")
    }

  private fun get(request: PlexRequest): String {
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 10_000
      readTimeout = 20_000
      requestMethod = "GET"
      request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }

    try {
      val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (connection.responseCode !in 200..299) {
        error("Plex returned HTTP ${connection.responseCode}: ${body.toPlexErrorSnippet()}")
      }
      return body
    } finally {
      connection.disconnect()
    }
  }
}

private fun String.toPlexErrorSnippet(): String =
  replace(Regex("<[^>]+>"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { "No response body" }
    .take(120)

object PlexXmlParser {
  fun libraries(xml: String): List<PlexLibrary> = parseTags(xml, "Directory").mapNotNull { attrs ->
    val type = attrs["type"].orEmpty()
    if (type != "artist" && type != "album" && type != "track") return@mapNotNull null
    PlexLibrary(
      key = attrs["key"].orEmpty(),
      title = attrs["title"].orEmpty(),
      type = type,
    )
  }

  fun albums(xml: String): List<PlexAlbum> = parseTags(xml, "Directory").mapNotNull { attrs ->
    val key = attrs["key"].orEmpty()
    val title = attrs["title"].orEmpty()
    if (key.isBlank() || title.isBlank()) return@mapNotNull null
    PlexAlbum(
      key = key,
      title = title,
      artist = attrs["parentTitle"] ?: attrs["grandparentTitle"],
      thumb = attrs["thumb"],
    )
  }

  fun playlists(xml: String): List<PlexPlaylist> = parseTags(xml, "Playlist").mapNotNull { attrs ->
    val key = attrs["key"].orEmpty()
    val title = attrs["title"].orEmpty()
    if (key.isBlank() || title.isBlank()) return@mapNotNull null
    PlexPlaylist(
      key = key,
      title = title,
      durationMs = attrs["duration"]?.toLongOrNull(),
      thumb = attrs["composite"] ?: attrs["thumb"],
    )
  }

  fun tracks(xml: String): List<PlexTrack> = parseElements(xml, "Track").mapNotNull { trackElement ->
    val ratingKey = trackElement.attr("ratingKey")
    val key = trackElement.attr("key")
    val title = trackElement.attr("title")
    if (ratingKey.isBlank() || key.isBlank() || title.isBlank()) return@mapNotNull null
    PlexTrack(
      ratingKey = ratingKey,
      key = key,
      title = title,
      album = trackElement.optionalAttr("parentTitle"),
      artist = trackElement.optionalAttr("grandparentTitle") ?: trackElement.optionalAttr("originalTitle"),
      durationMs = trackElement.optionalAttr("duration")?.toLongOrNull(),
      thumb = trackElement.optionalAttr("thumb")
        ?: trackElement.optionalAttr("parentThumb")
        ?: trackElement.optionalAttr("grandparentThumb"),
      partKey = trackElement.firstNestedAttr("Part", "key") ?: trackElement.optionalAttr("mediaKey"),
      audioCodec = trackElement.firstNestedAttr("Media", "audioCodec"),
    )
  }

  fun sessions(xml: String): List<PlexSession> =
    parseElements(xml, "Track", "Video").mapNotNull { mediaElement ->
      val playerMachineIdentifier = mediaElement.firstNestedAttr("Player", "machineIdentifier").orEmpty()
      if (playerMachineIdentifier.isBlank()) return@mapNotNull null
      val title = mediaElement.attr("title")
      if (title.isBlank()) return@mapNotNull null
      PlexSession(
        sessionKey = mediaElement.optionalAttr("sessionKey")
          ?: mediaElement.optionalAttr("ratingKey")
          ?: playerMachineIdentifier,
        title = title,
        subtitle = listOfNotNull(
          mediaElement.optionalAttr("grandparentTitle") ?: mediaElement.optionalAttr("originalTitle"),
          mediaElement.optionalAttr("parentTitle"),
          mediaElement.firstNestedAttr("User", "title"),
        ).joinToString(" - ").ifBlank { null },
        type = mediaElement.optionalAttr("type") ?: mediaElement.tagName,
        playerTitle = mediaElement.firstNestedAttr("Player", "title"),
        playerMachineIdentifier = playerMachineIdentifier,
        state = mediaElement.firstNestedAttr("Player", "state"),
      )
    }

  private fun parseTags(xml: String, tag: String): List<Map<String, String>> {
    return parseElements(xml, tag).map { element ->
      val attrs = element.attributes
      (0 until attrs.length).associate { attrIndex ->
        val attr = attrs.item(attrIndex)
        attr.nodeName to attr.nodeValue
      }
    }
  }

  private fun parseElements(xml: String, vararg tags: String): List<Element> {
    val document = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(InputSource(xml.reader()))
    return tags.flatMap { tag ->
      val nodes = document.getElementsByTagName(tag)
      (0 until nodes.length).map { index -> nodes.item(index) as Element }
    }
  }

  private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

  private fun Element.optionalAttr(name: String): String? = getAttribute(name).takeIf { it.isNotBlank() }

  private fun Element.firstNestedAttr(tag: String, attr: String): String? {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length)
      .asSequence()
      .map { index -> (nodes.item(index) as Element).getAttribute(attr) }
      .firstOrNull { it.isNotBlank() }
  }
}
