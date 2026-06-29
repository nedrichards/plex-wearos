package com.nedrichards.plexwear.data

import junit.framework.TestCase.assertEquals
import org.junit.Test

class PlexXmlParserTest {
  @Test
  fun libraries_returnsMusicSectionsOnly() {
    val xml = """
      <MediaContainer>
        <Directory key="1" title="Music" type="artist" />
        <Directory key="2" title="Films" type="movie" />
      </MediaContainer>
    """.trimIndent()

    val libraries = PlexXmlParser.libraries(xml)

    assertEquals(listOf(PlexLibrary("1", "Music", "artist")), libraries)
  }

  @Test
  fun albums_readsArtistAndArtwork() {
    val xml = """
      <MediaContainer>
        <Directory key="/library/metadata/10/children" title="Album" parentTitle="Artist" thumb="/thumb.jpg" />
      </MediaContainer>
    """.trimIndent()

    val albums = PlexXmlParser.albums(xml)

    assertEquals("Album", albums.single().title)
    assertEquals("Artist", albums.single().artist)
    assertEquals("/thumb.jpg", albums.single().thumb)
  }

  @Test
  fun tracks_readsPlayablePartKeyAndMetadata() {
    val xml = """
      <MediaContainer>
        <Track ratingKey="99" key="/library/metadata/99" title="Track" parentTitle="Album" grandparentTitle="Artist" duration="123000">
          <Media id="10" audioCodec="flac">
            <Part id="20" key="/library/parts/99/file.flac" />
          </Media>
        </Track>
      </MediaContainer>
    """.trimIndent()

    val track = PlexXmlParser.tracks(xml).single()

    assertEquals("99", track.ratingKey)
    assertEquals("Track", track.title)
    assertEquals("Album", track.album)
    assertEquals("Artist", track.artist)
    assertEquals(123000L, track.durationMs)
    assertEquals("/library/parts/99/file.flac", track.partKey)
    assertEquals("flac", track.audioCodec)
  }
}
