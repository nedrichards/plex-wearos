package com.nedrichards.plexwear.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class PlexAuthXmlParserTest {
  @Test
  fun pin_readsIdAndCode() {
    val xml = """<pin id="12345" code="XGQF" authToken="" expiresIn="900" />"""

    val pin = PlexAuthXmlParser.pin(xml)

    assertEquals(PlexPin(id = "12345", code = "XGQF"), pin)
  }

  @Test
  fun authToken_returnsTokenWhenLinked() {
    val xml = """<pin id="12345" code="ABCD" authToken="account-token" />"""

    assertEquals("account-token", PlexAuthXmlParser.authToken(xml))
  }

  @Test
  fun authToken_returnsNullWhileWaiting() {
    val xml = """<pin id="12345" code="ABCD" authToken="" />"""

    assertNull(PlexAuthXmlParser.authToken(xml))
  }

  @Test
  fun credentials_prefersOwnedNonRelayLocalServer() {
    val xml = """
      <MediaContainer>
        <Device name="Shared" product="Plex Media Server" provides="server" accessToken="shared-token" owned="0">
          <Connection protocol="https" address="shared.example.test" uri="https://shared.example.test:32400" local="0" relay="0" />
        </Device>
        <Device name="Mine" product="Plex Media Server" provides="server" accessToken="server-token" owned="1">
          <Connection protocol="http" address="192.168.1.2" uri="http://192.168.1.2:32400" local="1" relay="0" />
          <Connection protocol="https" address="mine.example.test" uri="https://mine.example.test:32400" local="0" relay="0" />
        </Device>
      </MediaContainer>
    """.trimIndent()

    val credentials = PlexAuthXmlParser.credentials(xml)

    assertEquals("http://192.168.1.2:32400", credentials.serverUrl)
    assertEquals("server-token", credentials.token)
  }

  @Test
  fun credentials_usesRemoteHttpsWhenNoLocalConnectionExists() {
    val xml = """
      <MediaContainer>
        <Device name="Mine" product="Plex Media Server" provides="server" accessToken="server-token" owned="1">
          <Connection protocol="https" address="mine.example.test" uri="https://mine.example.test:32400" local="0" relay="0" />
          <Connection protocol="https" address="relay.example.test" uri="https://relay.example.test:443" local="0" relay="1" />
        </Device>
      </MediaContainer>
    """.trimIndent()

    val credentials = PlexAuthXmlParser.credentials(xml)

    assertEquals("https://mine.example.test:32400", credentials.serverUrl)
    assertEquals("server-token", credentials.token)
  }

  @Test
  fun credentials_usesLocalServerWhenItIsOnlyConnection() {
    val xml = """
      <MediaContainer>
        <Device name="Mine" product="Plex Media Server" provides="server" accessToken="server-token" owned="1">
          <Connection protocol="http" address="192.168.1.2" uri="http://192.168.1.2:32400/" local="1" relay="0" />
        </Device>
      </MediaContainer>
    """.trimIndent()

    val credentials = PlexAuthXmlParser.credentials(xml)

    assertEquals("http://192.168.1.2:32400", credentials.serverUrl)
    assertEquals("server-token", credentials.token)
  }

  @Test
  fun credentials_usesAccountTokenWhenServerTokenIsMissing() {
    val xml = """
      <MediaContainer>
        <Device name="Mine" product="Plex Media Server" provides="server" owned="1">
          <Connection protocol="http" address="192.168.1.2" uri="http://192.168.1.2:32400" local="1" relay="0" />
        </Device>
      </MediaContainer>
    """.trimIndent()

    val credentials = PlexAuthXmlParser.credentials(xml, accountToken = "account-token")

    assertEquals("http://192.168.1.2:32400", credentials.serverUrl)
    assertEquals("account-token", credentials.token)
  }

  @Test
  fun credentials_readsLowercaseResourceResponses() {
    val xml = """
      <mediaContainer>
        <resource name="Mine" product="Plex Media Server" provides="server" accessToken="server-token" owned="1">
          <connection protocol="https" address="mine.example.test" uri="https://mine.example.test:32400" local="0" relay="0" />
        </resource>
      </mediaContainer>
    """.trimIndent()

    val credentials = PlexAuthXmlParser.credentials(xml)

    assertEquals("https://mine.example.test:32400", credentials.serverUrl)
    assertEquals("server-token", credentials.token)
  }

  @Test
  fun credentials_fallsBackToDebugServerUrlWithAccountToken() {
    val xml = """<MediaContainer size="0" />"""

    val credentials = PlexAuthXmlParser.credentials(
      xml = xml,
      accountToken = "account-token",
      fallbackServerUrl = "http://192.168.1.2:32400/",
    )

    assertEquals("http://192.168.1.2:32400", credentials.serverUrl)
    assertEquals("account-token", credentials.token)
  }
}
