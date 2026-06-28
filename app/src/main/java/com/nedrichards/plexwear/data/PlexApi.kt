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
  suspend fun get(credentials: PlexCredentials, path: String, query: Map<String, String> = emptyMap()): String =
    withContext(Dispatchers.IO) {
      val request = PlexRequestBuilder.build(credentials, path, query)
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
          error("Plex returned HTTP ${connection.responseCode}: ${body.take(120)}")
        }
        body
      } finally {
        connection.disconnect()
      }
    }
}

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

  private fun parseElements(xml: String, tag: String): List<Element> {
    val document = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(InputSource(xml.reader()))
    val nodes = document.getElementsByTagName(tag)
    return (0 until nodes.length).map { index -> nodes.item(index) as Element }
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
