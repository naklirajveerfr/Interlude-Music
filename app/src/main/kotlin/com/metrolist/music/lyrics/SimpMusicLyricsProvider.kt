package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableSimpMusicKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

object SimpMusicLyricsProvider : LyricsProvider {
    private const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"
    private const val DURATION_TOLERANCE_SEC = 10.0

    override val name = "SimpMusic"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableSimpMusicKey] ?: true

    private class Track(
        val duration: Double,
        val rich: String?,
        val synced: String?,
        val plain: String?,
    )

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private suspend fun fetchTracks(videoId: String): List<Track> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(BASE_URL + URLEncoder.encode(videoId, "UTF-8")).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("User-Agent", "SimpMusicLyrics/1.0")
                    if (conn.responseCode != 200) return@withContext emptyList()
                    val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    if (!root.optBoolean("success", false)) return@withContext emptyList()
                    val data = root.optJSONArray("data") ?: return@withContext emptyList()
                    (0 until data.length()).mapNotNull { i ->
                        data.optJSONObject(i)?.let { o ->
                            Track(
                                duration = o.optDouble("duration", 0.0),
                                rich = o.text("richSyncLyrics"),
                                synced = o.text("syncedLyrics"),
                                plain = o.text("plainLyrics"),
                            )
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun pickBest(tracks: List<Track>, duration: Int): Track? {
        if (duration <= 0) return tracks.firstOrNull()
        return tracks
            .filter { abs(it.duration - duration) <= DURATION_TOLERANCE_SEC }
            .minByOrNull { abs(it.duration - duration) }
    }

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> =
        try {
            val track = pickBest(fetchTracks(id), duration) ?: error("Lyrics unavailable")
            Result.success(track.rich ?: track.synced ?: track.plain ?: error("Lyrics unavailable"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        val tracks = fetchTracks(id)
        val sorted = if (duration > 0) tracks.sortedBy { abs(it.duration - duration) } else tracks
        var count = 0
        var plainSent = false
        for (track in sorted) {
            if (count > 4) break
            if (duration > 0 && abs(track.duration - duration) > DURATION_TOLERANCE_SEC) continue
            val timed = track.rich ?: track.synced
            if (timed != null) {
                count++
                callback(timed)
            }
            val plain = track.plain
            if (plain != null && !plainSent) {
                count++
                plainSent = true
                callback(plain)
            }
        }
    }
}
