/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SpotifyTrack(val title: String, val artists: List<String>)

data class SpotifyPlaylist(val name: String, val tracks: List<SpotifyTrack>, val coverUrl: String? = null)

/**
 * Reads a public Spotify playlist from its embed page, so no API key or login is needed.
 * The embed page exposes at most the first 100 tracks of a playlist.
 */
object SpotifyPlaylistService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val playlistIdRegex = Regex("""playlist[/:]([A-Za-z0-9]{22})""")
    private val nextDataRegex =
        Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    // The embed page's JSON layout changes between releases, so locate the track list by key instead of a fixed path.
    private fun findObjectWithKey(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node
                for (name in node.keys()) {
                    findObjectWithKey(node.opt(name), key)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findObjectWithKey(node.opt(i), key)?.let { return it }
            }
        }
        return null
    }

    private fun extractCoverUrl(root: JSONObject, entity: JSONObject): String? {
        val sources = (findObjectWithKey(entity, "coverArt") ?: findObjectWithKey(root, "coverArt"))
            ?.optJSONObject("coverArt")?.optJSONArray("sources")
            ?: (findObjectWithKey(entity, "visualIdentity") ?: findObjectWithKey(root, "visualIdentity"))
                ?.optJSONObject("visualIdentity")?.optJSONArray("image")
            ?: return null
        var best: String? = null
        var bestSize = -1
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val size = maxOf(item.optInt("width"), item.optInt("maxWidth"))
            if (size > bestSize) {
                best = url
                bestSize = size
            }
        }
        return best
    }

    fun parsePlaylistId(input: String): String? = playlistIdRegex.find(input.trim())?.groupValues?.get(1)

    suspend fun fetchPlaylist(playlistId: String): Result<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://open.spotify.com/embed/playlist/$playlistId")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                .build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Spotify returned ${response.code}")
                response.body?.string() ?: error("Empty response from Spotify")
            }

            val json = nextDataRegex.find(html)?.groupValues?.get(1)
                ?: error("Could not read playlist. Make sure it is public.")
            val root = JSONObject(json)
            val entity = findObjectWithKey(root, "trackList")
                ?: error("No tracks found in playlist")
            val trackList = entity.getJSONArray("trackList")
            val tracks = buildList {
                for (i in 0 until trackList.length()) {
                    val item = trackList.getJSONObject(i)
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) continue
                    val artists = item.optString("subtitle")
                        .split(",", " ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    add(SpotifyTrack(title, artists))
                }
            }
            if (tracks.isEmpty()) error("No tracks found in playlist")

            val name = entity.optString("name").ifEmpty { entity.optString("title") }.ifEmpty { "Spotify playlist" }
            SpotifyPlaylist(name, tracks, extractCoverUrl(root, entity))
        }
    }
}
