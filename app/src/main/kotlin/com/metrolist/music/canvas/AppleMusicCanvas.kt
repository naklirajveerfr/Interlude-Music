/*
 * Adapted from ArchiveTune (github.com/rukamori/ArchiveTune), GPL-3.0.
 * © Rukamori — original notice retained per GPL-3.0 Sections 4 & 5.
 */

package com.metrolist.music.canvas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.Locale

/**
 * Animated ("motion") album artwork from Apple Music's public web catalog.
 * Uses the same short-lived web-player token the Apple Music website uses.
 */
object AppleMusicCanvas {
    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val blacklist = listOf("playlist", "set list", "essentials", "dj mix", "mixed", "apple music", "today's hits", "session")

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 25_000
                socketTimeoutMillis = 25_000
            }
            expectSuccess = false
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getBySongArtist(song: String, artist: String, storefront: String = "us"): CanvasArtwork? {
        val query = if (song.contains(artist, ignoreCase = true)) song else "$artist $song"
        val response = catalogGet("$AMP_BASE_URL/v1/catalog/$storefront/search") {
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", "10")
            parameter("extend", "editorialVideo")
            parameter("include", "albums")
        }
        if (response.status != HttpStatusCode.OK) return null
        val results = response.body<JsonObject>()["results"]?.jsonObject
            ?.get("songs")?.jsonObject?.get("data")?.jsonArray ?: return null

        val ranked = results.mapNotNull { score(it.jsonObject, song, artist) }.sortedByDescending { it.first }
        for ((score, obj) in ranked) {
            if (score < 12) continue
            val attributes = obj["attributes"]?.jsonObject ?: continue
            val songName = attributes.string("name")
            val artistName = attributes.string("artistName")
            val albumId = albumIdOf(obj, attributes)?.takeUnless { it.startsWith("pl.") } ?: continue

            val direct = attributes["editorialVideo"]?.jsonObject?.let(::videoUrls)
            val urls = direct?.takeIf { it.first != null || it.second != null }
                ?: albumVideoUrls(albumId, storefront)
                ?: continue
            return CanvasArtwork(
                name = songName,
                artist = artistName,
                albumId = albumId,
                albumName = attributes.string("albumName").ifBlank { attributes.string("collectionName") },
                animated = urls.first,
                animatedVertical = urls.second,
            )
        }
        return null
    }

    private suspend fun albumVideoUrls(albumId: String, storefront: String): Pair<String?, String?>? {
        val response = catalogGet("$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId") {
            parameter("extend", "editorialVideo")
        }
        if (response.status != HttpStatusCode.OK) return null
        val attributes = response.body<JsonObject>()["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("attributes")?.jsonObject ?: return null
        if (blacklist.any { attributes.string("name").contains(it, ignoreCase = true) }) return null
        return attributes["editorialVideo"]?.jsonObject?.let(::videoUrls)
            ?.takeIf { it.first != null || it.second != null }
    }

    /** Returns (square/raw animated url, tall animated url). */
    private fun videoUrls(ev: JsonObject): Pair<String?, String?> {
        fun JsonObject.url(): String? =
            listOf("video", "videoUrl", "hlsUrl", "url").firstNotNullOfOrNull { this[it]?.jsonPrimitive?.contentOrNull }
        val tall = ev["motionDetailTall"]?.jsonObject?.url()
        val animated = ev["motionDetailRaw"]?.jsonObject?.url()
            ?: ev["motionDetailSquare"]?.jsonObject?.url()
            ?: ev["motionDetailStatic"]?.jsonObject?.url()
            ?: tall
        return animated to tall
    }

    private fun score(obj: JsonObject, song: String, artist: String): Pair<Int, JsonObject>? {
        val attributes = obj["attributes"]?.jsonObject ?: return null
        val name = attributes.string("name")
        val resultArtist = attributes.string("artistName")
        val collection = attributes.string("albumName").ifBlank { attributes.string("collectionName") }
        if (blacklist.any { name.contains(it, true) || collection.contains(it, true) }) return null
        if (!(resultArtist.contains(artist, true) || artist.contains(resultArtist, true))) return null

        var score = if (resultArtist.equals(artist, true)) 10 else 5
        score += when {
            name.equals(song, true) -> 15
            name.contains(song, true) || song.contains(name, true) -> 7
            else -> -10
        }
        for (word in listOf("deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus")) {
            val inSong = song.contains(word, true)
            val inResult = name.contains(word, true)
            if (inSong && inResult) score += 5 else if (inResult && !inSong) score -= 3
        }
        return score to obj
    }

    private fun albumIdOf(obj: JsonObject, attributes: JsonObject): String? {
        obj["relationships"]?.jsonObject?.get("albums")?.jsonObject?.get("data")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.let { return it }
        attributes["collectionId"]?.jsonPrimitive?.contentOrNull?.let { return it }
        // Track URLs look like .../album/name/ID?i=songId
        val id = attributes.string("url").substringAfter("/album/", "").substringBefore("?").substringAfterLast("/", "")
        return id.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    // --- Web-player token -------------------------------------------------------------

    private suspend fun catalogGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse {
        suspend fun request(token: String) = client.get(url) {
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", USER_AGENT)
            block()
        }
        val token = webToken()
        val response = request(token)
        return if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            request(webToken(rejected = token))
        } else {
            response
        }
    }

    private class Token(val value: String, val expiresAtSeconds: Long)

    private val tokenMutex = Mutex()
    private var cachedToken: Token? = null
    private val tokenPattern = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val scriptPattern = Regex("""<script\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private suspend fun webToken(rejected: String? = null): String = tokenMutex.withLock {
        cachedToken
            ?.takeIf { it.value != rejected && it.expiresAtSeconds > System.currentTimeMillis() / 1_000 + 300 }
            ?.let { return@withLock it.value }

        val pageUrl = URI("https://music.apple.com/us/new")
        val page = fetchText(pageUrl.toString())
        findToken(page)?.let { cachedToken = it; return@withLock it.value }

        val scripts = scriptPattern.findAll(page)
            .map { pageUrl.resolve(it.groupValues[1]) }
            .filter { it.scheme == "https" && it.host == "music.apple.com" && it.path.startsWith("/assets/index") }
            .distinct()
            .take(3)
        for (script in scripts) {
            val token = findToken(fetchText(script.toString())) ?: continue
            cachedToken = token
            return@withLock token.value
        }
        throw IOException("Apple Music web token unavailable")
    }

    private suspend fun fetchText(url: String): String {
        val response = client.get(url) {
            header("User-Agent", USER_AGENT)
            header("Cache-Control", "no-cache")
        }
        if (response.status != HttpStatusCode.OK) throw IOException("Apple Music token request failed: ${response.status.value}")
        return response.bodyAsText()
    }

    private fun findToken(text: String): Token? = tokenPattern.findAll(text).firstNotNullOfOrNull { match ->
        runCatching {
            val payload = json.parseToJsonElement(
                String(Base64.getUrlDecoder().decode(match.value.substringAfter('.').substringBefore('.')), Charsets.UTF_8),
            ).jsonObject
            if (payload["iss"]?.jsonPrimitive?.contentOrNull != "AMPWebPlay") return@runCatching null
            val expiry = payload["exp"]?.jsonPrimitive?.longOrNull ?: return@runCatching null
            Token(match.value, expiry).takeIf { expiry > System.currentTimeMillis() / 1_000 + 300 }
        }.getOrNull()
    }
}
