package com.metrolist.music.automix

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A regular beat grid in the song's own time: beats fall at anchorSec + k * periodSec. */
data class BeatGrid(
    val periodSec: Double,
    val anchorSec: Double,
    val strength: Float,
    /** Which of the four beats of a bar is the downbeat: beat k (at anchorSec + k * periodSec) opens a bar when k mod 4 equals this. -1 if unknown. */
    val barPhase: Int = -1,
    val barStrength: Float = 0f,
) {
    val isReliable: Boolean get() = strength >= MIN_STRENGTH
    val isBarReliable: Boolean get() = barPhase >= 0 && barStrength >= MIN_BAR_STRENGTH

    /** Seconds from [posSec] to the next beat (0 when exactly on one). */
    fun secondsToNextBeat(posSec: Double): Double {
        val x = (posSec - anchorSec) / periodSec
        return (kotlin.math.ceil(x) - x) * periodSec
    }

    /** Seconds from [posSec] to the next downbeat (0 when exactly on one). Only meaningful when [isBarReliable]. */
    fun secondsToNextDownbeat(posSec: Double): Double {
        val bar = 4.0 * periodSec
        val x = (posSec - anchorSec - barPhase * periodSec) / bar
        return (kotlin.math.ceil(x) - x) * bar
    }

    companion object {
        const val MIN_STRENGTH = 1.6f
        const val MIN_BAR_STRENGTH = 1.2f
    }
}

data class TrackAnalysis(
    val bpm: Float?,
    val confidence: Float,
    val introSilenceSec: Float,
    val silentTailSec: Float,
    val quietTailSec: Float,
    val tailKnown: Boolean = false,
    val beatGrid: BeatGrid? = null,
    val tempoStable: Boolean = true,
    val key: KeyEstimate? = null,
) {
    val hasReliableBpm: Boolean get() = bpm != null && confidence >= MIN_CONFIDENCE

    companion object {
        const val MIN_CONFIDENCE = 1.25f
    }
}

internal class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { -kotlin.math.sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n)

    init {
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val tr = re[j + half] * cosT[k] - im[j + half] * sinT[k]
                    val ti = re[j + half] * sinT[k] + im[j + half] * cosT[k]
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

private class FrameStream(
    private val fluxFromSec: Double,
    private val fluxToSec: Double,
) {
    companion object {
        const val N = 1024
        const val H = 512
    }

    private val fft = Fft(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val buf = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val prevLog = FloatArray(N / 2)
    private var filled = 0
    private var frameIdx = 0
    private var maxBin = 1
    private var lowMaxBin = 1
    private var hasPrev = false

    var rate = 0
        private set
    var t0 = 0.0
        private set
    var fluxStartSec = 0.0
        private set
    val rms = ArrayList<Float>()
    val flux = ArrayList<Float>()

    // Onset strength of the bass range only (up to about 200 Hz), where the kick that marks a downbeat lives.
    val lowFlux = ArrayList<Float>()

    val hopSec: Double get() = H.toDouble() / rate
    val endSec: Double get() = t0 + (frameIdx.toLong() * H + N).toDouble() / rate

    fun frameStartSec(i: Int): Double = t0 + i.toDouble() * H / rate

    fun push(mono: FloatArray, n: Int, sampleRate: Int, tSec: Double) {
        if (rate == 0) {
            rate = sampleRate
            t0 = tSec
            maxBin = min(N / 2 - 1, (5000.0 / (rate / 2.0) * (N / 2)).toInt()).coerceAtLeast(2)
            lowMaxBin = min(maxBin, (200.0 / (rate / 2.0) * (N / 2)).toInt()).coerceAtLeast(1)
        }
        for (i in 0 until n) {
            buf[filled++] = mono[i]
            if (filled == N) {
                frame()
                System.arraycopy(buf, H, buf, 0, N - H)
                filled = N - H
            }
        }
    }

    private fun frame() {
        var e = 0.0
        for (i in 0 until N) e += (buf[i] * buf[i]).toDouble()
        rms.add(sqrt(e / N).toFloat())
        val t = frameStartSec(frameIdx)
        if (t >= fluxFromSec && t <= fluxToSec) {
            for (i in 0 until N) {
                re[i] = buf[i] * win[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            var f = 0f
            var lowF = 0f
            for (k in 1..maxBin) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / N * 4f
                val l = ln1p(100f * mag)
                if (hasPrev) {
                    val d = l - prevLog[k]
                    if (d > 0f) {
                        f += d
                        if (k <= lowMaxBin) lowF += d
                    }
                }
                prevLog[k] = l
            }
            if (flux.isEmpty()) fluxStartSec = t
            flux.add(if (hasPrev) f else 0f)
            lowFlux.add(if (hasPrev) lowF else 0f)
            hasPrev = true
        }
        frameIdx++
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class AutomixAnalyzer(
    private val tmpDir: File,
    private val caches: () -> List<Cache>,
    private val fetchHead: (suspend (mediaId: String, out: File, maxBytes: Long) -> Boolean)? = null,
) {
    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val mutex = Mutex()

    fun get(mediaId: String): TrackAnalysis? = results[mediaId]

    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val polling = ConcurrentHashMap.newKeySet<String>()
    private val fetchAttempts = ConcurrentHashMap<String, Int>()

    /** Returns null if not enough of the song is cached yet (retry later) or if analysis failed. */
    suspend fun analyze(mediaId: String, needTail: Boolean = false): TrackAnalysis? {
        results[mediaId]?.let { if (it.tailKnown || !needTail) return it }
        if (failed.contains(mediaId)) return null
        return mutex.withLock {
            results[mediaId]?.let { if (it.tailKnown || !needTail) return@withLock it }
            withContext(Dispatchers.Default) {
                val file = File(tmpDir, mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".tmp")
                try {
                    tmpDir.mkdirs()
                    var copied = copyFromCache(mediaId, file, needTail)
                    if (copied < 0 && results[mediaId] == null) {
                        // Not cached (yet): use whatever head is cached, else pull just the start of the stream.
                        copied = copyFromCache(mediaId, file, false)
                        if (copied < 0 && fetchHead != null && fetchAttempts.merge(mediaId, 1, Int::plus)!! <= MAX_FETCH_ATTEMPTS) {
                            if (fetchHead.invoke(mediaId, file, HEAD_FETCH_BYTES) && file.length() > 0L) copied = 0
                        }
                    }
                    if (copied < 0) return@withContext null
                    val r = analyzeFile(file, copied == 1)
                    if (r == null) {
                        failed.add(mediaId)
                        Timber.tag("AutomixDebug").w("analysis produced nothing for $mediaId")
                        return@withContext null
                    }
                    results[mediaId] = r
                    Timber.tag("AutomixDebug").d(
                        "id=$mediaId bpm=${r.bpm} conf=${r.confidence} intro=${r.introSilenceSec}s " +
                            "silentTail=${r.silentTailSec}s quietTail=${r.quietTailSec}s tailKnown=${r.tailKnown} " +
                            "beatPeriod=${r.beatGrid?.periodSec} beatStrength=${r.beatGrid?.strength} " +
                            "barPhase=${r.beatGrid?.barPhase} barStrength=${r.beatGrid?.barStrength}",
                    )
                    r
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.tag("AutomixDebug").w("analysis failed for $mediaId: ${e.message}")
                    failed.add(mediaId)
                    null
                } finally {
                    file.delete()
                }
            }
        }
    }

    /** Polls until enough of the song is cached, then analyzes it. Safe to call repeatedly. */
    suspend fun analyzeWhenReady(mediaId: String, needTail: Boolean, maxWaitMs: Long = 300_000L): TrackAnalysis? {
        results[mediaId]?.let { if (it.tailKnown || !needTail) return it }
        val key = "$mediaId:$needTail"
        if (!polling.add(key)) return null
        try {
            val end = android.os.SystemClock.elapsedRealtime() + maxWaitMs
            while (android.os.SystemClock.elapsedRealtime() < end) {
                val r = analyze(mediaId, needTail)
                if (r != null) return r
                if (failed.contains(mediaId)) return null
                kotlinx.coroutines.delay(4_000)
            }
            return null
        } finally {
            polling.remove(key)
        }
    }

    /** Returns -1 if not enough is cached, 0 if a usable start of the song was copied, 1 if all of it was. */
    private fun copyFromCache(mediaId: String, out: File, needFull: Boolean): Int {
        for (cache in caches()) {
            try {
                val len = ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))
                if (len <= 0L) continue
                val cachedLen = cache.getCachedLength(mediaId, 0, len)
                val full = cachedLen >= len
                if (needFull && !full) continue
                if (!full && cachedLen < 500_000L) continue
                val take = if (full) len else cachedLen
                val ds = CacheDataSource.Factory().setCache(cache).createDataSource()
                val spec =
                    DataSpec.Builder()
                        .setUri(Uri.parse("automix://$mediaId"))
                        .setKey(mediaId)
                        .setLength(take)
                        .build()
                ds.open(spec)
                try {
                    out.outputStream().use { o ->
                        val b = ByteArray(64 * 1024)
                        while (true) {
                            val n = ds.read(b, 0, b.size)
                            if (n < 0) break
                            o.write(b, 0, n)
                        }
                    }
                } finally {
                    ds.close()
                }
                if (out.length() > 0L) {
                    Timber.tag("AutomixDebug").d("copied id=$mediaId bytes=$take/$len full=$full")
                    return if (full) 1 else 0
                }
            } catch (e: Exception) {
                Timber.tag("AutomixDebug").w("cache read failed: ${e.message}")
            }
        }
        return -1
    }

    private companion object {
        const val HEAD_FETCH_BYTES = 1_000_000L
        const val MAX_FETCH_ATTEMPTS = 2
        const val TEMPO_WINDOW_SEC = 30.0
        const val TEMPO_AGREE_TOLERANCE = 0.03f
        const val DISAGREE_CONFIDENCE_FACTOR = 0.8f
        const val BEAT_TIGHTNESS = 100f
        const val BEAT_WINDOW_SEC = 14.0
    }

    private fun probeDurationSec(path: String): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true &&
                    f.containsKey(MediaFormat.KEY_DURATION)
                ) {
                    return f.getLong(MediaFormat.KEY_DURATION) / 1e6
                }
            }
            return -1.0
        } finally {
            ex.release()
        }
    }

    private fun decode(
        path: String,
        startSec: Double,
        endSec: Double,
        sink: (FloatArray, Int, Int, Double) -> Unit,
    ) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) return
            ex.selectTrack(track)
            if (startSec > 0.0) ex.seekTo((startSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(8192)
            while (!outputDone) {
                if (!inputDone) {
                    val ib = c.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val ibuf = c.getInputBuffer(ib)!!
                        val sz = ex.readSampleData(ibuf, 0)
                        val st = ex.sampleTime
                        if (sz < 0 || (st >= 0 && st / 1e6 > endSec)) {
                            c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ib, 0, sz, st, 0)
                            ex.advance()
                        }
                    }
                }
                val ob = c.dequeueOutputBuffer(info, 10_000)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4
                } else if (ob >= 0) {
                    if (info.size > 0) {
                        val bb = c.getOutputBuffer(ob)!!
                        bb.position(info.offset)
                        bb.limit(info.offset + info.size)
                        bb.order(ByteOrder.nativeOrder())
                        val frames: Int
                        if (floatPcm) {
                            val fb = bb.asFloatBuffer()
                            frames = fb.remaining() / ch
                            if (frames > mono.size) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var s = 0f
                                for (k in 0 until ch) s += fb.get()
                                mono[f] = s / ch
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            frames = sb.remaining() / ch
                            if (frames > mono.size) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var s = 0f
                                for (k in 0 until ch) s += sb.get() / 32768f
                                mono[f] = s / ch
                            }
                        }
                        sink(mono, frames, rate, info.presentationTimeUs / 1e6)
                    }
                    c.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            ex.release()
        }
    }

    private fun median(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        return list.sorted()[list.size / 2]
    }

    // Spectral flux minus its local mean, so only onsets that stand out from their surroundings remain.
    private fun onsetEnvelope(fluxRaw: List<Float>, hopSec: Double): FloatArray {
        val n = fluxRaw.size
        val w = max(3, (0.5 / hopSec).toInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + fluxRaw[i]
        val e = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - w)
            val b = min(n, i + w + 1)
            val mean = (prefix[b] - prefix[a]) / (b - a)
            e[i] = max(0.0, fluxRaw[i] - mean).toFloat()
        }
        return e
    }

    // Dynamic-programming beat tracker (after Ellis 2007): picks the beat sequence that lands on onset peaks while
    // keeping the spacing close to the given tempo, then fits a regular grid through it. Times are on the song's
    // clock: startSec is the time of the first flux frame.
    private fun trackBeats(flux: List<Float>, lowFlux: List<Float>, hopSec: Double, startSec: Double, bpm: Double): BeatGrid? {
        val n = flux.size
        val period = 60.0 / (bpm * hopSec)
        if (period < 4.0 || n < period * 8) return null
        val e = onsetEnvelope(flux, hopSec)
        var mean = 0.0
        for (v in e) mean += v
        mean /= n
        var variance = 0.0
        for (v in e) variance += (v - mean) * (v - mean)
        val std = sqrt(variance / n)
        if (std <= 0.0 || mean <= 0.0) return null
        val onset = FloatArray(n) { (e[it] / std).toFloat() }

        val minLag = max(1, (period / 2).toInt())
        val maxLag = (period * 2).toInt() + 1
        val score = FloatArray(n)
        val back = IntArray(n) { -1 }
        for (t in 0 until n) {
            var best = 0f
            var bestPrev = -1
            for (lag in minLag..maxLag) {
                val p = t - lag
                if (p < 0) break
                val dev = kotlin.math.ln(lag / period)
                val s = score[p] - BEAT_TIGHTNESS * (dev * dev).toFloat()
                if (s > best) {
                    best = s
                    bestPrev = p
                }
            }
            score[t] = onset[t] + best
            back[t] = bestPrev
        }

        // The chain has to end near the end of the audio, so start the backtrace from the best of the last beat.
        val tailStart = max(0, n - period.toInt() - 1)
        var t = tailStart
        for (i in tailStart until n) if (score[i] > score[t]) t = i
        val beats = ArrayList<Int>()
        while (t >= 0) {
            beats.add(t)
            t = back[t]
        }
        beats.reverse()
        if (beats.size < 8) return null

        // Least-squares line through (beat number, beat time), dropping beats that sit off the line and refitting once.
        val times = DoubleArray(beats.size) { startSec + beats[it] * hopSec }
        val nominal = 60.0 / bpm
        var p = nominal
        var anchor = times[0]
        val inlier = BooleanArray(times.size) { true }
        for (pass in 0 until 2) {
            var c = 0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in times.indices) {
                if (!inlier[i]) continue
                val x = kotlin.math.round((times[i] - times[0]) / p)
                c++
                sx += x
                sy += times[i]
                sxx += x * x
                sxy += x * times[i]
            }
            val denom = c * sxx - sx * sx
            if (c < 6 || denom <= 0.0) return null
            p = (c * sxy - sx * sy) / denom
            anchor = (sy - p * sx) / c
            for (i in times.indices) {
                val x = kotlin.math.round((times[i] - times[0]) / p)
                inlier[i] = kotlin.math.abs(times[i] - (anchor + p * x)) <= 0.15 * p
            }
        }
        if (kotlin.math.abs(p - nominal) > 0.1 * nominal) return null

        // Strength: how much stronger the onsets on the grid are than onsets in general.
        var sum = 0.0
        var cnt = 0
        for (i in beats.indices) {
            if (!inlier[i]) continue
            val f = beats[i]
            var peak = onset[f]
            if (f > 0) peak = max(peak, onset[f - 1])
            if (f < n - 1) peak = max(peak, onset[f + 1])
            sum += peak
            cnt++
        }
        if (cnt < 6) return null
        val strength = (sum / cnt / (mean / std)).toFloat()
        val (barPhase, barStrength) = findDownbeat(e, lowFlux, hopSec, startSec, p, anchor)
        return BeatGrid(p, anchor, strength, barPhase, barStrength)
    }

    // Picks which of the four beats of a bar is the downbeat: the beat position whose onsets (bass onsets weighted in)
    // stand out most over many bars. The strength is that position's average against the average of the other three,
    // so a steady four-on-the-floor kick, which gives no clue, comes out unreliable.
    private fun findDownbeat(
        onset: FloatArray,
        lowFlux: List<Float>,
        hopSec: Double,
        startSec: Double,
        period: Double,
        anchor: Double,
    ): Pair<Int, Float> {
        val none = Pair(-1, 0f)
        val n = onset.size
        if (lowFlux.size != n) return none
        val low = onsetEnvelope(lowFlux, hopSec)
        var onsetMean = 0.0
        var lowMean = 0.0
        for (i in 0 until n) {
            onsetMean += onset[i]
            lowMean += low[i]
        }
        onsetMean /= n
        lowMean /= n
        if (onsetMean <= 0.0 || lowMean <= 0.0) return none

        val sum = DoubleArray(4)
        val count = IntArray(4)
        val kMin = kotlin.math.ceil((startSec - anchor) / period).toInt()
        val kMax = kotlin.math.floor((startSec + (n - 1) * hopSec - anchor) / period).toInt()
        for (k in kMin..kMax) {
            val f = kotlin.math.round((anchor + k * period - startSec) / hopSec).toInt()
            if (f < 1 || f > n - 2) continue
            val broad = max(onset[f - 1], max(onset[f], onset[f + 1])) / onsetMean
            val bass = max(low[f - 1], max(low[f], low[f + 1])) / lowMean
            val phase = k.mod(4)
            sum[phase] += broad + bass
            count[phase]++
        }
        if (count.any { it < 3 }) return none
        val mean = DoubleArray(4) { sum[it] / count[it] }
        val best = mean.indices.maxByOrNull { mean[it] } ?: return none
        val others = (mean.sum() - mean[best]) / 3.0
        if (others <= 0.0) return none
        return Pair(best, (mean[best] / others).toFloat())
    }

    /** Beat grid of the audio just before [positionSec] of a playing song, or null if it can't be found in time. */
    suspend fun beatGridBefore(mediaId: String, positionSec: Double, bpm: Float): BeatGrid? =
        withContext(Dispatchers.Default) {
            val file = File(tmpDir, "beat_" + System.nanoTime() + "_" + mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".tmp")
            try {
                tmpDir.mkdirs()
                if (copyFromCache(mediaId, file, false) < 0) return@withContext null
                val from = max(0.0, positionSec - BEAT_WINDOW_SEC)
                val stream = FrameStream(from, positionSec)
                decode(file.absolutePath, from, positionSec + 0.5) { m, n, r, t ->
                    ensureActive()
                    stream.push(m, n, r, t)
                }
                // Not enough of the song is cached to reach the playing position.
                if (stream.rate == 0 || stream.endSec < positionSec - 1.0) return@withContext null
                val grid = trackBeats(stream.flux, stream.lowFlux, stream.hopSec, stream.fluxStartSec, bpm.toDouble())
                Timber.tag("AutomixDebug").d(
                    "beat grid before ${positionSec}s: period=${grid?.periodSec} strength=${grid?.strength} " +
                        "barPhase=${grid?.barPhase} barStrength=${grid?.barStrength} (bpm hint $bpm)",
                )
                grid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("AutomixDebug").w("beat window failed for $mediaId: ${e.message}")
                null
            } finally {
                file.delete()
            }
        }

    private fun estimateBpm(fluxRaw: List<Float>, hopSec: Double): Pair<Float, Float>? {
        val n = fluxRaw.size
        if (n < 400) return null
        val e = onsetEnvelope(fluxRaw, hopSec)

        fun corr(lag: Double): Double {
            val li = lag.toInt()
            val frac = lag - li
            val cnt = n - li - 1
            if (cnt < n / 4) return 0.0
            var s = 0.0
            for (i in 0 until cnt) s += e[i] * (e[i + li] * (1 - frac) + e[i + li + 1] * frac)
            return s / cnt
        }

        fun score(bpm: Double): Double {
            val lag = 60.0 / (bpm * hopSec)
            val o = log2(bpm / 125.0) / 0.8
            val prior = exp(-0.5 * o * o)
            return prior * (corr(lag) + 0.5 * corr(2 * lag) + 0.25 * corr(4 * lag))
        }

        var best = 0.0
        var bestBpm = 0.0
        var sum = 0.0
        var cnt = 0
        var bpm = 70.0
        while (bpm <= 180.0) {
            val s = score(bpm)
            sum += s
            cnt++
            if (s > best) {
                best = s
                bestBpm = bpm
            }
            bpm += 0.25
        }
        if (bestBpm == 0.0 || sum <= 0.0) return null
        var refined = bestBpm
        var refinedScore = best
        var b2 = bestBpm - 1.0
        while (b2 <= bestBpm + 1.0) {
            val s = score(b2)
            if (s > refinedScore) {
                refinedScore = s
                refined = b2
            }
            b2 += 0.05
        }
        return Pair(refined.toFloat(), (refinedScore / (sum / cnt)).toFloat())
    }

    // Estimates tempo on up to two consecutive windows of the song's start and only trusts the result fully when
    // they agree, since one window can land on an intro, a breakdown or a half/double-time reading.
    private fun estimateBpmWindows(flux: List<Float>, hopSec: Double): Pair<Float, Float>? {
        val windowLen = (TEMPO_WINDOW_SEC / hopSec).toInt()
        val first = estimateBpm(flux.subList(0, min(flux.size, windowLen)), hopSec)
        // A short second window (a partly cached song) is too noisy to be allowed to veto the first one.
        val second =
            if (flux.size - windowLen >= windowLen / 2) {
                estimateBpm(flux.subList(windowLen, min(flux.size, 2 * windowLen)), hopSec)
            } else {
                null
            }
        if (first == null || second == null) {
            Timber.tag("AutomixDebug").d("tempo windows: w1=$first w2=$second (single window)")
            return first ?: second
        }
        val ratio = second.first / first.first
        val agree = kotlin.math.abs(ratio - 1f) <= TEMPO_AGREE_TOLERANCE
        Timber.tag("AutomixDebug").d("tempo windows: w1=$first w2=$second agree=$agree")
        if (agree) {
            val total = first.second + second.second
            val bpm = (first.first * first.second + second.first * second.second) / total
            return Pair(bpm, total / 2f)
        }
        val best = if (first.second >= second.second) first else second
        return Pair(best.first, best.second * DISAGREE_CONFIDENCE_FACTOR)
    }

    private fun analyzeFile(file: File, full: Boolean): TrackAnalysis? {
        val path = file.absolutePath
        val duration = try { probeDurationSec(path) } catch (e: Exception) { -1.0 }
        val bpmFrom = 4.0
        val bpmTo = 64.0

        val head = FrameStream(bpmFrom, bpmTo)
        val tarsos = TarsosBpmDetector()
        val chroma = ChromaStream()
        try {
            decode(path, 0.0, bpmTo + 1.0) { m, n, r, t ->
                head.push(m, n, r, t)
                if (t >= bpmFrom && t <= bpmTo) {
                    tarsos.push(m, n, r)
                    chroma.push(m, n, r)
                }
            }
        } catch (e: Exception) {
            Timber.tag("AutomixDebug").w("head decode stopped: ${e.message}")
        }
        if (head.rate == 0 || head.rms.size < 50) return null
        val ref = median(head.rms)
        if (ref <= 0f) return null

        val firstLoud = head.rms.indexOfFirst { it >= 0.03f * ref }
        val intro = if (firstLoud < 0) 0f else min(8.0, head.frameStartSec(firstLoud)).toFloat()
        // TarsosDSP gives the tempo; the spectral estimator only covers songs it can't call reliably.
        val tarsosEst = runCatching { tarsos.estimate() }.getOrNull()?.takeIf { it.confidence >= TrackAnalysis.MIN_CONFIDENCE }
        val est = tarsosEst?.let { Pair(it.bpm, it.confidence) } ?: estimateBpmWindows(head.flux, head.hopSec)

        var silentTail = 0f
        var quietTail = 0f
        var tailKnown = false
        if (full && duration > 20) {
            val tail = FrameStream(Double.MAX_VALUE, Double.MAX_VALUE)
            try {
                decode(path, max(0.0, duration - 30.0), duration + 10.0) { m, n, r, t -> tail.push(m, n, r, t) }
            } catch (e: Exception) {
                Timber.tag("AutomixDebug").w("tail decode stopped: ${e.message}")
            }
            if (tail.rate > 0 && tail.rms.size > 20) {
                val end = tail.endSec
                val frameLen = FrameStream.N.toDouble() / tail.rate
                val lastLoud = tail.rms.indexOfLast { it >= 0.05f * ref }
                val lastMid = tail.rms.indexOfLast { it >= 0.35f * ref }
                if (lastLoud >= 0) {
                    silentTail = (end - (tail.frameStartSec(lastLoud) + frameLen)).coerceIn(0.0, 20.0).toFloat()
                }
                if (lastMid >= 0) {
                    quietTail = (end - (tail.frameStartSec(lastMid) + frameLen)).coerceIn(0.0, 30.0).toFloat()
                }
                tailKnown = true
            }
        }
        val grid =
            est
                ?.takeIf { it.second >= TrackAnalysis.MIN_CONFIDENCE }
                ?.let { runCatching { trackBeats(head.flux, head.lowFlux, head.hopSec, head.fluxStartSec, it.first.toDouble()) }.getOrNull() }
        return TrackAnalysis(est?.first, est?.second ?: 0f, intro, silentTail, quietTail, tailKnown, grid, tarsosEst?.stable ?: true, runCatching { chroma.estimate() }.getOrNull())
    }
}
