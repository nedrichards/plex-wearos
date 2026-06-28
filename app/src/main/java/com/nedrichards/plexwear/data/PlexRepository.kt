package com.nedrichards.plexwear.data

import com.nedrichards.plexwear.auth.PlexCredentials

class PlexRepository(private val api: PlexApi) {
  suspend fun musicLibraries(credentials: PlexCredentials): List<PlexLibrary> =
    PlexXmlParser.libraries(api.get(credentials, "/library/sections"))

  suspend fun recentMusic(credentials: PlexCredentials): List<PlexTrack> =
    PlexXmlParser.tracks(api.get(credentials, "/library/recentlyAdded", mapOf("type" to "10")))

  suspend fun albums(credentials: PlexCredentials, library: PlexLibrary): List<PlexAlbum> =
    PlexXmlParser.albums(api.get(credentials, "/library/sections/${library.key}/albums"))

  suspend fun playlists(credentials: PlexCredentials): List<PlexPlaylist> =
    PlexXmlParser.playlists(api.get(credentials, "/playlists", mapOf("playlistType" to "audio")))

  suspend fun tracksForAlbum(credentials: PlexCredentials, album: PlexAlbum): List<PlexTrack> =
    PlexXmlParser.tracks(api.get(credentials, album.key))

  suspend fun tracksForPlaylist(credentials: PlexCredentials, playlist: PlexPlaylist): List<PlexTrack> =
    PlexXmlParser.tracks(api.get(credentials, playlist.key))
}
