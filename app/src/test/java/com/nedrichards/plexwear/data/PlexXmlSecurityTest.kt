package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexAuthXmlParser
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException

class PlexXmlSecurityTest {
  @Test fun rejectsDtdsBeforeReadingFilesOrExpandingEntities() {
    val secret = Files.createTempFile("plex-xxe-", ".txt")
    try {
      Files.write(secret, "private-test-content".toByteArray())
      val declarations = listOf(
        "<!DOCTYPE MediaContainer SYSTEM '${secret.toUri()}'>",
        "<!DOCTYPE MediaContainer SYSTEM 'http://127.0.0.1:1/external.dtd'>",
        "<!DOCTYPE MediaContainer [<!ENTITY xxe SYSTEM '${secret.toUri()}'>]>",
        "<!DOCTYPE MediaContainer [<!ENTITY % xxe SYSTEM '${secret.toUri()}'>%xxe;]>",
        "<!DOCTYPE MediaContainer [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;'>]>",
      )
      declarations.forEach { declaration ->
        val failure = runCatching {
          parsePlexXml("$declaration<MediaContainer>&xxe;</MediaContainer>")
        }.exceptionOrNull()
        assertTrue("Expected DTD rejection for $declaration, got $failure", failure is SAXException)
        assertEquals("DOCTYPE declarations are not supported", failure?.message)
      }
    } finally { Files.deleteIfExists(secret) }
  }

  @Test fun authenticationAlsoRejectsDtds() {
    val failure = runCatching {
      PlexAuthXmlParser.authToken("<!DOCTYPE pin [<!ENTITY token 'injected'>]><pin authToken='&token;' />")
    }.exceptionOrNull()
    assertTrue(failure is SAXException)
    assertEquals("DOCTYPE declarations are not supported", failure?.message)
  }

  @Test fun preservesEscapedAttributesNestedElementsAndText() {
    val document = parsePlexXml("""
      <?xml version="1.0"?>
      <MediaContainer><Track title="A &amp; B &#x1F3B5; &quot;quoted&quot;">
        <Media><Part key="/a?x=1&amp;y=2" /></Media><![CDATA[text < >]]>&amp;
      </Track></MediaContainer>
    """.trimIndent())
    val track = document.getElementsByTagName("Track").item(0)
    assertEquals("A & B 🎵 \"quoted\"", track.attributes.getNamedItem("title").nodeValue)
    assertEquals("/a?x=1&y=2", document.getElementsByTagName("Part").item(0).attributes.getNamedItem("key").nodeValue)
    assertTrue(track.textContent.contains("text < >&"))
  }

  @Test fun rejectsMalformedXmlAndUndeclaredEntities() {
    listOf("<MediaContainer>", "<MediaContainer>&unknown;</MediaContainer>", "<a/><b/>").forEach { xml ->
      assertTrue(runCatching { parsePlexXml(xml) }.exceptionOrNull() is SAXException)
    }
  }
}
