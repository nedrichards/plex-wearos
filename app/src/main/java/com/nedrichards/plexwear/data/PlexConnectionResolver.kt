package com.nedrichards.plexwear.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.nedrichards.plexwear.auth.PlexCredentials

/** Route preferences last only for the current network, never change media identity. */
object PlexConnectionResolver {
  private val routes = RoutePreferences()
  private var initialized = false

  @Synchronized fun initialize(context: Context) {
    if (initialized) return
    initialized = true
    val manager = context.getSystemService(ConnectivityManager::class.java)
    manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) = invalidate()
      override fun onLost(network: Network) = invalidate()
    })
  }

  fun orderedCredentials(credentials: PlexCredentials): PlexCredentials {
    val urls = routes.ordered(credentials.cacheScope, credentials.serverUrls)
    return if (urls.isEmpty()) credentials else credentials.copy(serverUrl = urls.first(), alternateServerUrls = urls.drop(1))
  }

  fun succeeded(credentials: PlexCredentials, serverUrl: String) = routes.succeeded(credentials.cacheScope, serverUrl)
  fun failed(credentials: PlexCredentials, serverUrl: String) = routes.failed(credentials.cacheScope, serverUrl)
  fun orderedUrls(scope: String, urls: List<String>): List<String> = routes.ordered(scope, urls)
  fun succeeded(scope: String, serverUrl: String) = routes.succeeded(scope, serverUrl)
  fun failed(scope: String, serverUrl: String) = routes.failed(scope, serverUrl)
  fun invalidate() = routes.clear()
}

internal class RoutePreferences(private val now: () -> Long = System::currentTimeMillis) {
  private val successful = mutableMapOf<String, String>()
  private val failedUntil = mutableMapOf<Pair<String, String>, Long>()

  @Synchronized fun ordered(scope: String, urls: List<String>): List<String> = urls.sortedWith(
    compareBy<String> { (failedUntil[scope to it] ?: 0) > now() }
      .thenBy { it != successful[scope] },
  )
  @Synchronized fun succeeded(scope: String, url: String) {
    successful[scope] = url
    failedUntil.remove(scope to url)
  }
  @Synchronized fun failed(scope: String, url: String) {
    failedUntil[scope to url] = now() + 60_000
    if (successful[scope] == url) successful.remove(scope)
  }
  @Synchronized fun clear() { successful.clear(); failedUntil.clear() }
}
