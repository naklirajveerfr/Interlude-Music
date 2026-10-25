/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.shazamkit.models.RecognitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * Starts the recognized song at the position the original track has reached by now, so it
 * sounds like the app simply kept playing what it was listening to.
 *
 * Shazam reports where in the track the recorded sample started ([RecognitionResult.matchOffsetMs]);
 * adding the time elapsed since the recording began gives the live position of the original.
 * The song is prepared paused, seeked until the seek latency is compensated, and only then played.
 */
object RecognitionPlayback {
    private const val TAG = "RecognitionPlayback"

    // Results older than this (e.g. recognized by the widget long ago) are not synced.
    private const val MAX_RESULT_AGE_MS = 60_000L
    private const val READY_TIMEOUT_MS = 20_000L
    private const val SEEK_TIMEOUT_MS = 10_000L
    private const val SYNC_TOLERANCE_MS = 100L
    private const val MAX_SYNC_PASSES = 5
    private const val MAX_LEAD_MS = 3_000L
    private const val END_MARGIN_MS = 2_000L
    private const val POLL_MS = 25L

    // Outlives the recognition screen so leaving it mid-sync cannot leave the song paused.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun playInSync(
        connection: PlayerConnection,
        result: RecognitionResult,
    ) {
        val offsetMs = result.matchOffsetMs ?: return
        val sampleStartMs = result.sampleStartElapsedMs ?: return
        if (SystemClock.elapsedRealtime() - sampleStartMs > MAX_RESULT_AGE_MS) return

        job?.cancel()
        job =
            scope.launch {
                try {
                    syncPlayback(connection, result, offsetMs, sampleStartMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Synced playback failed")
                }
            }
    }

    private fun livePositionMs(
        offsetMs: Long,
        sampleStartMs: Long,
    ): Long = offsetMs + (SystemClock.elapsedRealtime() - sampleStartMs)

    private suspend fun syncPlayback(
        connection: PlayerConnection,
        result: RecognitionResult,
        offsetMs: Long,
        sampleStartMs: Long,
    ) {
        val song = findSong(result)
        if (song == null) {
            Timber.tag(TAG).w("No YouTube Music match for '%s' by %s", result.title, result.artist)
            return
        }
        val durationMs = song.duration?.let { it * 1000L }

        connection.playQueue(
            YouTubeQueue(WatchEndpoint(videoId = song.id), song.toMediaMetadata()),
            playWhenReady = false,
        )

        val player = awaitReady(connection, song.id)
        if (player == null) {
            Timber.tag(TAG).w("Player did not get ready for %s in time", song.id)
            return
        }

        if (durationMs != null && livePositionMs(offsetMs, sampleStartMs) >= durationMs - END_MARGIN_MS) {
            Timber.tag(TAG).d("Original track already near its end, playing from the start")
            player.play()
            return
        }

        // lead = how far ahead of "now" to aim, so the seek latency is absorbed while paused.
        var lead = 0L
        for (pass in 0 until MAX_SYNC_PASSES) {
            connection.seekTo(livePositionMs(offsetMs, sampleStartMs) + lead)
            awaitSeekSettled(player)
            val drift = livePositionMs(offsetMs, sampleStartMs) - player.currentPosition
            Timber.tag(TAG).d("Sync pass %d: lead=%dms drift=%dms", pass, lead, drift)
            if (abs(drift) <= SYNC_TOLERANCE_MS) break
            lead = (lead + drift).coerceIn(0L, MAX_LEAD_MS)
        }
        player.play()
    }

    private suspend fun findSong(result: RecognitionResult): SongItem? =
        withContext(Dispatchers.IO) {
            YouTube
                .search("${result.title} ${result.artist}", YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                ?.let { songs ->
                    songs.firstOrNull { it.title.equals(result.title, ignoreCase = true) }
                        ?: songs.firstOrNull()
                }
        }

    private suspend fun awaitReady(
        connection: PlayerConnection,
        mediaId: String,
    ): ExoPlayer? {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val player = runCatching { connection.player }.getOrNull()
            if (player != null &&
                player.currentMediaItem?.mediaId == mediaId &&
                player.playbackState == Player.STATE_READY
            ) {
                return player
            }
            delay(POLL_MS)
        }
        return null
    }

    private suspend fun awaitSeekSettled(player: ExoPlayer) {
        // Give the player a moment to enter BUFFERING after the seek request.
        delay(POLL_MS)
        val deadline = SystemClock.elapsedRealtime() + SEEK_TIMEOUT_MS
        while (player.playbackState == Player.STATE_BUFFERING && SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
        }
    }
}
