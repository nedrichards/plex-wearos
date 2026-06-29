package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials
import java.net.URLEncoder

data class PlexRequest(
  val url: String,
  val headers: Map<String, String>,
)

object PlexRequestBuilder {
  private val clientHeaders = mapOf(
    "Accept" to "application/xml",
    "X-Plex-Client-Identifier" to "plex-wearos-debug",
    "X-Plex-Device" to "Wear OS",
    "X-Plex-Device-Name" to "Plex Wear",
    "X-Plex-Platform" to "Android",
    "X-Plex-Product" to "Plex Wear",
    "X-Plex-Version" to "0.1",
  )

  fun build(credentials: PlexCredentials, path: String, query: Map<String, String> = emptyMap()): PlexRequest {
    require(credentials.isConfigured) { "Plex credentials are not configured" }
    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    val parameters = query + ("X-Plex-Token" to credentials.token)
    val queryString = parameters.entries.joinToString("&") { (key, value) ->
      "${key.urlEncode()}=${value.urlEncode()}"
    }
    return PlexRequest(
      url = "${credentials.serverUrl.trimEnd('/')}$normalizedPath?$queryString",
      headers = clientHeaders + ("X-Plex-Token" to credentials.token),
    )
  }

  fun streamingUrl(credentials: PlexCredentials, track: PlexTrack): String {
    val directKey = track.partKey ?: track.key
    return build(credentials, directKey).url
  }

  fun transcodeUrl(credentials: PlexCredentials, track: PlexTrack): String = build(
    credentials = credentials,
    path = "/music/:/transcode/universal/start",
    query = clientHeaders + mapOf(
      "path" to track.key,
      "protocol" to "http",
      "directPlay" to "0",
      "directStream" to "0",
      "audioCodec" to "aac",
      "audioBitrate" to "192",
      "maxAudioChannels" to "2",
      "X-Plex-Container-Start" to "0",
      "X-Plex-Container-Size" to "1",
    ),
  ).url

  private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
