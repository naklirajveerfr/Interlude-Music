/*
 * Adapted from ArchiveTune (github.com/rukamori/ArchiveTune), GPL-3.0.
 * © Rukamori — original notice retained per GPL-3.0 Sections 4 & 5.
 */

package com.metrolist.music.canvas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale

/**
 * Looks up animated artwork (a looping video) for a song.
 * Results, including misses, are cached in memory for the session.
 */
object CanvasRepository {
    private const val TAG = "Canvas"
    private const val MAX_CACHE = 200
    private val providers = listOf(CanvasSource.BETTER_LYRICS, CanvasSource.TIDAL)

    private val cache = object : LinkedHashMap<String, CanvasArtwork?>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanvasArtwork?>?) = size > MAX_CACHE
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 12_000
                requestTimeoutMillis = 18_000
                socketTimeoutMillis = 18_000
            }
            expectSuccess = false
        }
    }

    /** Returns the animation URL for the song, or null if no source has one. */
    suspend fun resolve(
        title: String,
        artist: String,
        source: CanvasSource,
    ): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTitle(title)
        val cleanArtist = cleanArtist(artist)
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return@withContext null

        val key = "${source.name}|${cleanTitle.lowercase(Locale.ROOT)}|${cleanArtist.lowercase(Locale.ROOT)}"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key]?.animationUrl }

        val found = providers.firstNotNullOfOrNull { provider ->
            if (!source.accepts(provider)) return@firstNotNullOfOrNull null
            val artwork = try {
                withTimeoutOrNull(20_000L) {
                    when (provider) {
                        CanvasSource.BETTER_LYRICS -> betterLyrics(cleanTitle, cleanArtist)
                        CanvasSource.TIDAL -> tidal(cleanTitle, cleanArtist)
                        CanvasSource.ALL -> null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Canvas lookup failed: %s", provider)
                null
            }
            artwork?.takeIf { it.matchesSong(cleanTitle, cleanArtist) && !it.animationUrl.isNullOrBlank() }
        }
        synchronized(cache) { cache[key] = found }
        found?.animationUrl
    }

    private suspend fun betterLyrics(song: String, artist: String): CanvasArtwork? {
        val response = client.get("https://artwork.boidu.dev/") {
            parameter("s", song)
            parameter("a", artist)
            parameter("storefront", "us")
        }
        return if (response.status == HttpStatusCode.OK) response.body<CanvasArtwork>() else null
    }

    private suspend fun tidal(song: String, artist: String): CanvasArtwork? {
        val results = client.get("https://api.tidal.com/v1/search") {
            header("X-Tidal-Token", "vNVdglQOjFJJGG2U")
            parameter("query", "$artist $song")
            parameter("types", "TRACKS")
            parameter("countryCode", "US")
            parameter("limit", 10)
        }.takeIf { it.status == HttpStatusCode.OK }?.body<TidalSearch>() ?: return null

        for (track in results.tracks.items) {
            val album = track.album ?: continue
            val video = tidalUrl(album.videoCover, "videos", "1280x1280.mp4") ?: continue
            val artwork = CanvasArtwork(
                name = track.title,
                artist = track.artists.joinToString(", ") { it.name },
                albumName = album.title,
                static = tidalUrl(album.cover, "images", "1280x1280.jpg"),
                animated = video,
            )
            if (artwork.matchesSong(song, artist)) return artwork
        }
        return null
    }

    private val uuidPattern = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    private fun tidalUrl(id: String?, kind: String, file: String): String? =
        id?.takeIf(uuidPattern::matches)?.let { "https://resources.tidal.com/$kind/${it.replace('-', '/')}/$file" }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(Regex("\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex(
                    "\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-')
            .trim()

    private fun cleanArtist(raw: String): String =
        raw.split(
            Regex("(?:\\s*,\\s*|\\s*&\\s*|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)", RegexOption.IGNORE_CASE),
            limit = 2,
        ).firstOrNull().orEmpty().replace(Regex("\\s+"), " ").trim()

    @Serializable
    private data class TidalSearch(val tracks: TidalTracks)

    @Serializable
    private data class TidalTracks(val items: List<TidalTrack> = emptyList())

    @Serializable
    private data class TidalTrack(
        val title: String,
        val artists: List<TidalArtist> = emptyList(),
        val album: TidalAlbum? = null,
    )

    @Serializable
    private data class TidalArtist(val name: String)

    @Serializable
    private data class TidalAlbum(
        val title: String? = null,
        val cover: String? = null,
        val videoCover: String? = null,
    )
}
