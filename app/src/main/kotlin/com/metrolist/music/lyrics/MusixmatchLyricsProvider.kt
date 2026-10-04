package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableMusixmatchKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MusixmatchLyricsProvider : LyricsProvider {
    private const val BASE = "https://apic-desktop.musixmatch.com/ws/1.1"
    private const val APP_ID = "web-desktop-app-v1.0"

    override val name = "Musixmatch"

    private val tokenMutex = Mutex()

    @Volatile
    private var userToken: String? = null

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableMusixmatchKey] ?: false

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Cookie", "AWSELBCORS=0; AWSELB=0")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun getToken(force: Boolean = false): String =
        tokenMutex.withLock {
            val cached = userToken
            if (!force && cached != null) return@withLock cached
            val token =
                JSONObject(httpGet("$BASE/token.get?app_id=$APP_ID&format=json"))
                    .getJSONObject("message")
                    .getJSONObject("body")
                    .getString("user_token")
            if (token.isBlank() || token.startsWith("UpgradeOnly")) error("Musixmatch token unavailable")
            userToken = token
            token
        }

    private fun fetch(
        token: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): JSONObject =
        JSONObject(
            httpGet(
                "$BASE/macro.subtitles.get?format=json&subtitle_format=lrc&app_id=$APP_ID" +
                    "&usertoken=$token&q_track=${enc(title)}&q_artist=${enc(artist.substringBefore(",").trim())}" +
                    "&q_duration=$duration&q_album=${enc(album.orEmpty())}",
            ),
        )

    private fun parse(json: JSONObject): String? {
        val calls =
            json
                .getJSONObject("message")
                .optJSONObject("body")
                ?.optJSONObject("macro_calls") ?: return null

        fun body(call: String) = calls.optJSONObject(call)?.optJSONObject("message")?.optJSONObject("body")

        body("track.subtitles.get")
            ?.optJSONArray("subtitle_list")
            ?.optJSONObject(0)
            ?.optJSONObject("subtitle")
            ?.optString("subtitle_body")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return body("track.lyrics.get")
            ?.optJSONObject("lyrics")
            ?.optString("lyrics_body")
            ?.substringBefore("*******")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                var json = fetch(getToken(), title, artist, duration, album)
                val status =
                    json
                        .getJSONObject("message")
                        .getJSONObject("header")
                        .optInt("status_code")
                if (status == 401) {
                    json = fetch(getToken(force = true), title, artist, duration, album)
                }
                parse(json) ?: error("No lyrics found")
            }
        }
}
