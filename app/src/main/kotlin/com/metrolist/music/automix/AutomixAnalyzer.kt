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

data class TrackAnalysis(
    val bpm: Float?,
    val confidence: Float,
    val introSilenceSec: Float,
    val silentTailSec: Float,
    val quietTailSec: Float,
    val tailKnown: Boolean = false,
) {
    val hasReliableBpm: Boolean get() = bpm != null && confidence >= MIN_CONFIDENCE

    companion object {
        const val MIN_CONFIDENCE = 1.25f
    }
}

private class Fft(private val n: Int) {
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
    private var hasPrev = false

    var rate = 0
        private set
    var t0 = 0.0
        private set
    val rms = ArrayList<Float>()
    val flux = ArrayList<Float>()

    val hopSec: Double get() = H.toDouble() / rate
    val endSec: Double get() = t0 + (frameIdx.toLong() * H + N).toDouble() / rate

    fun frameStartSec(i: Int): Double = t0 + i.toDouble() * H / rate

    fun push(mono: FloatArray, n: Int, sampleRate: Int, tSec: Double) {
        if (rate == 0) {
            rate = sampleRate
            t0 = tSec
            maxBin = min(N / 2 - 1, (5000.0 / (rate / 2.0) * (N / 2)).toInt()).coerceAtLeast(2)
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
            for (k in 1..maxBin) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / N * 4f
                val l = ln1p(100f * mag)
                if (hasPrev) {
                    val d = l - prevLog[k]
                    if (d > 0f) f += d
                }
                prevLog[k] = l
            }
            flux.add(if (hasPrev) f else 0f)
            hasPrev = true
        }
        frameIdx++
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class AutomixAnalyzer(
    private val tmpDir: File,
    private val caches: () -> List<Cache>,
) {
    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val mutex = Mutex()

    fun get(mediaId: String): TrackAnalysis? = results[mediaId]

    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val polling = ConcurrentHashMap.newKeySet<String>()

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
                    val copied = copyFromCache(mediaId, file, needTail)
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
                            "silentTail=${r.silentTailSec}s quietTail=${r.quietTailSec}s tailKnown=${r.tailKnown}",
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

    private fun estimateBpm(fluxRaw: List<Float>, hopSec: Double): Pair<Float, Float>? {
        val n = fluxRaw.size
        if (n < 400) return null
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

    private fun analyzeFile(file: File, full: Boolean): TrackAnalysis? {
        val path = file.absolutePath
        val duration = try { probeDurationSec(path) } catch (e: Exception) { -1.0 }
        val bpmFrom = 4.0
        val bpmTo = 34.0

        val head = FrameStream(bpmFrom, bpmTo)
        try {
            decode(path, 0.0, bpmTo + 1.0) { m, n, r, t -> head.push(m, n, r, t) }
        } catch (e: Exception) {
            Timber.tag("AutomixDebug").w("head decode stopped: ${e.message}")
        }
        if (head.rate == 0 || head.rms.size < 50) return null
        val ref = median(head.rms)
        if (ref <= 0f) return null

        val firstLoud = head.rms.indexOfFirst { it >= 0.03f * ref }
        val intro = if (firstLoud < 0) 0f else min(8.0, head.frameStartSec(firstLoud)).toFloat()
        val est = estimateBpm(head.flux, head.hopSec)

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
        return TrackAnalysis(est?.first, est?.second ?: 0f, intro, silentTail, quietTail, tailKnown)
    }
}
