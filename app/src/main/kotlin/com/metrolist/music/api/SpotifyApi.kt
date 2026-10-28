/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SpotifyUserPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
    val imageUrl: String?,
)

/** Reads the logged-in user's data from the official Spotify Web API. */
object SpotifyApi {
    private const val BASE = "https://api.spotify.com/v1"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun get(context: Context, url: String): JSONObject = withContext(Dispatchers.IO) {
        val token = SpotifyAuth.accessToken(context) ?: error("Not logged in to Spotify")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Spotify returned ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    suspend fun fetchDisplayName(context: Context): String? =
        runCatching { get(context, "$BASE/me").optString("display_name").takeIf { it.isNotBlank() } }.getOrNull()

    suspend fun fetchPlaylists(context: Context): Result<List<SpotifyUserPlaylist>> = runCatching {
        val result = mutableListOf<SpotifyUserPlaylist>()
        var next: String? = "$BASE/me/playlists?limit=50"
        while (next != null) {
            val page = get(context, next)
            val items = page.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items?.optJSONObject(i) ?: continue
                val images = item.optJSONArray("images")
                val tracks = item.optJSONObject("tracks") ?: item.optJSONObject("items")
                result += SpotifyUserPlaylist(
                    id = item.getString("id"),
                    name = item.optString("name").ifBlank { "Spotify playlist" },
                    owner = item.optJSONObject("owner")?.optString("display_name").orEmpty(),
                    trackCount = tracks?.optInt("total") ?: 0,
                    imageUrl = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() },
                )
            }
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        result
    }

    /** Full track list of a playlist (all pages), using the user's login so private playlists work. */
    suspend fun fetchPlaylist(context: Context, playlist: SpotifyUserPlaylist): Result<SpotifyPlaylist> = runCatching {
        val tracks = mutableListOf<SpotifyTrack>()
        var next: String? = "$BASE/playlists/${playlist.id}/items?limit=50"
        var fellBack = false
        while (next != null) {
            val page = try {
                get(context, next)
            } catch (e: IllegalStateException) {
                // Older API path, in case the /items endpoint is not available for this app.
                if (fellBack || tracks.isNotEmpty()) throw e
                fellBack = true
                get(context, "$BASE/playlists/${playlist.id}/tracks?limit=50")
            }
            val items = page.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val entry = items?.optJSONObject(i) ?: continue
                val track = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                val title = track.optString("name").trim()
                if (title.isEmpty() || track.optString("type", "track") != "track") continue
                val artists = track.optJSONArray("artists")
                val names = buildList {
                    for (j in 0 until (artists?.length() ?: 0)) {
                        artists?.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                tracks += SpotifyTrack(title, names)
            }
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        if (tracks.isEmpty()) error("No tracks found in playlist")
        SpotifyPlaylist(playlist.name, tracks, playlist.imageUrl)
    }
}
