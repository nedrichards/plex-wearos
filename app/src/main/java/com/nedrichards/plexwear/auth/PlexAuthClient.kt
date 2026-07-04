package com.nedrichards.plexwear.auth

import com.nedrichards.plexwear.BuildConfig
import com.nedrichards.plexwear.data.PlexRequestBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class PlexPin(
  val id: String,
  val code: String,
)

class PlexAuthClient(
  private val plexTvUrl: String = "https://plex.tv",
) {
  suspend fun createPin(): PlexPin = withContext(Dispatchers.IO) {
    val body = request(
      method = "POST",
      path = "/api/v2/pins",
    )
    PlexAuthXmlParser.pin(body)
  }

  suspend fun pollPin(pinId: String): String? = withContext(Dispatchers.IO) {
    val body = request(
      method = "GET",
      path = "/api/v2/pins/$pinId",
    )
    PlexAuthXmlParser.authToken(body)
  }

  suspend fun credentialsForToken(token: String): PlexCredentials = withContext(Dispatchers.IO) {
    val body = request(
      method = "GET",
      path = "/api/v2/resources",
      query = mapOf(
        "includeHttps" to "1",
        "includeRelay" to "1",
        "X-Plex-Token" to token,
      ),
      token = token,
    )
    PlexAuthXmlParser.credentials(
      xml = body,
      accountToken = token,
      fallbackServerUrl = BuildConfig.DEBUG_PLEX_SERVER_URL.takeIf { BuildConfig.DEBUG },
    )
  }

  private fun request(
    method: String,
    path: String,
    query: Map<String, String> = emptyMap(),
    token: String? = null,
  ): String {
    val url = URL("$plexTvUrl$path${query.toQueryString()}")
    val headers = PlexRequestBuilder.clientHeaders
      .plus("Accept" to "application/xml")
      .let { baseHeaders ->
        if (token == null) baseHeaders else baseHeaders + ("X-Plex-Token" to token)
      }
    val connection = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 10_000
      readTimeout = 20_000
      requestMethod = method
      doInput = true
      if (method == "POST") doOutput = true
      headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }

    try {
      if (method == "POST") {
        connection.outputStream.use { }
      }
      val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (connection.responseCode !in 200..299) {
        error("Plex sign-in returned HTTP ${connection.responseCode}: ${body.take(120)}")
      }
      return body
    } finally {
      connection.disconnect()
    }
  }
}

object PlexAuthXmlParser {
  fun pin(xml: String): PlexPin {
    val element = parseRoot(xml)
    val id = element.attr("id")
    val code = element.attr("code")
    require(id.isNotBlank() && code.isNotBlank()) { "Plex did not return a sign-in code" }
    return PlexPin(id = id, code = code)
  }

  fun authToken(xml: String): String? =
    parseRoot(xml).optionalAttr("authToken")

  fun credentials(
    xml: String,
    accountToken: String? = null,
    fallbackServerUrl: String? = null,
  ): PlexCredentials {
    val document = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(InputSource(xml.reader()))
    val candidates = document.elements("Device", "device", "Resource", "resource")
      .filter { device ->
        device.attr("provides")
          .split(',')
          .map(String::trim)
          .any { it.equals("server", ignoreCase = true) }
      }
      .flatMap { device ->
        val token = device.attr("accessToken").ifBlank { accountToken.orEmpty() }
        if (token.isBlank()) return@flatMap emptySequence()
        val owned = device.attr("owned") == "1"
        device.connectionElements().mapNotNull { connection ->
          val uri = connection.attr("uri").trimEnd('/')
          if (uri.isBlank()) return@mapNotNull null
          PlexServerCandidate(
            serverUrl = uri,
            token = token,
            owned = owned,
            local = connection.attr("local") == "1",
            https = connection.attr("protocol").equals("https", ignoreCase = true) || uri.startsWith("https://"),
            relay = connection.attr("relay") == "1",
          )
        }
      }
      .toList()

    val selected = candidates
      .sortedWith(
        compareByDescending<PlexServerCandidate> { if (it.owned) 1 else 0 }
          .thenBy { if (it.relay) 1 else 0 }
          .thenByDescending { if (it.local) 1 else 0 }
          .thenByDescending { if (it.https) 1 else 0 },
      )
      .firstOrNull()

    if (selected != null) {
      return PlexCredentials(selected.serverUrl, selected.token)
    }

    val debugServerUrl = fallbackServerUrl.orEmpty().trim().trimEnd('/')
    if (debugServerUrl.isNotBlank() && !accountToken.isNullOrBlank()) {
      return PlexCredentials(debugServerUrl, accountToken)
    }

    error("No Plex server connection was found for this account")
  }

  private data class PlexServerCandidate(
    val serverUrl: String,
    val token: String,
    val owned: Boolean,
    val local: Boolean,
    val https: Boolean,
    val relay: Boolean,
  )

  private fun parseRoot(xml: String): Element =
    DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(InputSource(xml.reader()))
      .documentElement

  private fun Element.connectionElements(): Sequence<Element> {
    return elements("Connection", "connection")
  }

  private fun org.w3c.dom.Document.elements(vararg tags: String): Sequence<Element> =
    tags.asSequence().flatMap { tag ->
      val nodes = getElementsByTagName(tag)
      (0 until nodes.length).asSequence().map { index -> nodes.item(index) as Element }
    }

  private fun Element.elements(vararg tags: String): Sequence<Element> {
    return tags.asSequence().flatMap { tag ->
      val nodes = getElementsByTagName(tag)
      (0 until nodes.length).asSequence().map { index -> nodes.item(index) as Element }
    }
  }

  private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

  private fun Element.optionalAttr(name: String): String? = attr(name).takeIf { it.isNotBlank() }
}

private fun Map<String, String>.toQueryString(): String {
  if (isEmpty()) return ""
  return entries.joinToString(prefix = "?", separator = "&") { (key, value) ->
    "${key.urlEncode()}=${value.urlEncode()}"
  }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
