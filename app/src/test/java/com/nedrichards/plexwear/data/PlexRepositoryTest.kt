package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlexRepositoryTest {
  private val credentials = PlexCredentials(
    serverUrl = "https://plex.example.test",
    token = "token",
  )

  @Test
  fun albums_cachesParsedBrowseResponseForTheSameLibrary() = runTest {
    var requests = 0
    val repository = PlexRepository { _, path, _, _ ->
      requests += 1
      assertEquals("/library/sections/1/albums", path)
      albumsXml("Album $requests")
    }
    val library = PlexLibrary(key = "1", title = "Music", type = "artist")

    val first = repository.albums(credentials, library)
    val second = repository.albums(credentials, library)

    assertEquals(listOf(PlexAlbum("/library/metadata/1", "Album 1", "Artist", "/thumb/1")), first)
    assertEquals(first, second)
    assertEquals(1, requests)
  }

  @Test
  fun clearCache_forcesTheNextBrowseRequestToReload() = runTest {
    var requests = 0
    val repository = PlexRepository { _, _, _, _ ->
      requests += 1
      albumsXml("Album $requests")
    }
    val library = PlexLibrary(key = "1", title = "Music", type = "artist")

    repository.albums(credentials, library)
    repository.clearCache()
    val reloaded = repository.albums(credentials, library)

    assertEquals("Album 2", reloaded.single().title)
    assertEquals(2, requests)
  }

  @Test
  fun recentMusic_requestsOnlyTheVisibleHomeRows() = runTest {
    var query: Map<String, String> = emptyMap()
    val repository = PlexRepository { _, _, requestQuery, _ ->
      query = requestQuery
      "<MediaContainer />"
    }

    repository.recentMusic(credentials)

    assertEquals("10", query["type"])
    assertEquals("6", query["X-Plex-Container-Size"])
  }

  private fun albumsXml(title: String): String = """
    <MediaContainer>
      <Directory
        key="/library/metadata/1"
        title="$title"
        parentTitle="Artist"
        thumb="/thumb/1" />
    </MediaContainer>
  """.trimIndent()
}
