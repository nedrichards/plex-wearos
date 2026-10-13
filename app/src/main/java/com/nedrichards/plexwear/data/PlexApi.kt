package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.ensureActive
import org.w3c.dom.Element
import org.xml.sax.InputSource

class PlexApi {
  suspend fun get(
    credentials: PlexCredentials,
    path: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
  ): String {
    var lastFailure: Exception? = null
    for (serverUrl in PlexConnectionResolver.orderedCredentials(credentials).serverUrls) {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      try {
        val request = PlexRequestBuilder.build(credentials.forServerUrl(serverUrl), path, query, headers)
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
          connectTimeout = 3_000
          readTimeout = 12_000
          requestMethod = "GET"
          request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val body = connection.executeCancellable { http ->
          if (http.responseCode !in 200..299) throw PlexHttpException(http.responseCode)
          http.inputStream.bufferedReader().use { it.readText() }
        }
        PlexConnectionResolver.succeeded(credentials, serverUrl)
        return body
      } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
      } catch (failure: PlexHttpException) {
        if (failure.status == 401 || failure.status == 403) throw failure
        PlexConnectionResolver.failed(credentials, serverUrl)
        lastFailure = failure
      } catch (failure: Exception) {
        PlexConnectionResolver.failed(credentials, serverUrl)
        lastFailure = failure
      }
    }
    throw lastFailure ?: IllegalStateException("Plex credentials are not configured")
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
      playlistEntryId = trackElement.optionalAttr("playlistItemID"),
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
    val document = parsePlexXml(xml)
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

internal fun parsePlexXml(xml: String) =
  require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE declarations are not supported" }
    .let {
      DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .apply { setEntityResolver { _, _ -> error("External XML entities are not supported") } }
        .parse(InputSource(xml.reader()))
    }
