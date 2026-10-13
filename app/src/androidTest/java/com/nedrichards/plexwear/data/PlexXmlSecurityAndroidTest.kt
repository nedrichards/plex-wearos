package com.nedrichards.plexwear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nedrichards.plexwear.auth.PlexAuthXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.SAXException

@RunWith(AndroidJUnit4::class)
class PlexXmlSecurityAndroidTest {
  @Test fun androidParserRejectsExternalAndInternalDtds() {
    listOf(
      "<!DOCTYPE pin SYSTEM 'file:///proc/self/status'>",
      "<!DOCTYPE pin SYSTEM 'http://127.0.0.1:1/external.dtd'>",
      "<!DOCTYPE pin [<!ENTITY token SYSTEM 'file:///proc/self/status'>]>",
      "<!DOCTYPE pin [<!ENTITY % token SYSTEM 'file:///proc/self/status'>%token;]>",
      "<!DOCTYPE pin [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;'>]>",
    ).forEach { declaration ->
      val failure = runCatching {
        PlexAuthXmlParser.authToken("$declaration<pin authToken='&token;' />")
      }.exceptionOrNull()
      assertTrue("Expected DTD rejection, got $failure", failure is SAXException)
      assertEquals("DOCTYPE declarations are not supported", failure?.message)
    }
  }

  @Test fun androidParserPreservesMusicAndAuthenticationResponses() {
    assertEquals("token&value", PlexAuthXmlParser.authToken("<pin authToken='token&amp;value' />"))
    val track = PlexXmlParser.tracks("""
      <MediaContainer><Track ratingKey="1" key="/metadata/1" title="A &amp; B &#x1F3B5;">
        <Media audioCodec="flac"><Part key="/parts/1" /></Media>
      </Track></MediaContainer>
    """.trimIndent()).single()
    assertEquals("A & B 🎵", track.title)
    assertEquals("/parts/1", track.partKey)
    assertEquals("flac", track.audioCodec)
  }
}
