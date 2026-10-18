package com.metrolist.music.automix

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.beatroot.BeatRootOnsetEventHandler
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.onsets.ComplexOnsetDetector
import be.tarsos.dsp.onsets.OnsetHandler
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round

/** [stable] is false when the first and second half of the audio give different tempos. */
data class TempoEstimate(val bpm: Float, val confidence: Float, val stable: Boolean)

/**
 * Tempo estimate built on TarsosDSP: a complex-domain onset detector feeds the BeatRoot beat tracker and the
 * tempo is the spacing of the tracked beats. Feed mono PCM with [push], then read the result with [estimate].
 */
class TarsosBpmDetector {
    private val beatRoot = BeatRootOnsetEventHandler()
    private val onsets = ArrayList<Double>()
    private val buf = FloatArray(FFT_SIZE)
    private var filled = 0
    private var samples = 0L
    private var rate = 0
    private var broken = false
    private var detector: ComplexOnsetDetector? = null
    private var event: AudioEvent? = null

    fun push(mono: FloatArray, n: Int, sampleRate: Int) {
        if (broken) return
        try {
            if (detector == null) {
                rate = sampleRate
                event =
                    AudioEvent(TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)).also {
                        it.overlap = FFT_SIZE - HOP
                    }
                detector =
                    ComplexOnsetDetector(FFT_SIZE, PEAK_THRESHOLD, MIN_ONSET_GAP_SEC, SILENCE_DB).also {
                        it.setHandler(
                            OnsetHandler { time, salience ->
                                beatRoot.handleOnset(time, salience)
                                onsets.add(time)
                            },
                        )
                    }
            }
            val det = detector ?: return
            val ev = event ?: return
            for (i in 0 until n) {
                buf[filled++] = mono[i]
                if (filled == FFT_SIZE) {
                    ev.floatBuffer = buf
                    ev.setBytesProcessed(samples * BYTES_PER_SAMPLE)
                    det.process(ev)
                    samples += HOP
                    System.arraycopy(buf, HOP, buf, 0, FFT_SIZE - HOP)
                    filled = FFT_SIZE - HOP
                }
            }
        } catch (e: Exception) {
            broken = true
            Timber.tag("AutomixDebug").w("tarsos onset detection failed: ${e.message}")
        }
    }

    /**
     * Returns the tempo in the 70..180 range, or null when there is too little audio or no steady beat.
     * Confidence is on the same scale as the spectral estimator's, where [TrackAnalysis.MIN_CONFIDENCE] is reliable.
     */
    fun estimate(): TempoEstimate? {
        if (broken || rate == 0 || samples < MIN_AUDIO_SEC * rate || onsets.size < MIN_BEATS) return null
        val beats = ArrayList<Double>()
        beatRoot.trackBeats(OnsetHandler { time, _ -> beats.add(time) })
        if (beats.size < MIN_BEATS) return null

        val overall = period(beats) ?: return null
        val bpm = fold(60.0 / overall)

        // Confidence: how much more often a tracked beat lands on a real onset than a random beat would.
        var hits = 0
        var j = 0
        for (b in beats) {
            while (j < onsets.size && onsets[j] < b - SUPPORT_TOLERANCE_SEC) j++
            if (j < onsets.size && onsets[j] <= b + SUPPORT_TOLERANCE_SEC) hits++
        }
        val support = hits.toDouble() / beats.size
        val onsetSpan = onsets.last() - onsets.first()
        val density = if (onsetSpan > 1.0) onsets.size / onsetSpan else 0.0
        val chance = (1.0 - exp(-2.0 * SUPPORT_TOLERANCE_SEC * density)).coerceAtLeast(0.05)
        var confidence = support / chance

        // Like the spectral estimator, trust the tempo less when the two halves of the audio disagree.
        val mid = beats.size / 2
        val first = period(beats.subList(0, mid))
        val second = period(beats.subList(mid, beats.size))
        val agree = first == null || second == null || abs(fold(60.0 / first) / fold(60.0 / second) - 1.0) <= AGREE_TOLERANCE
        if (!agree) confidence *= DISAGREE_CONFIDENCE_FACTOR

        Timber.tag("AutomixDebug").d(
            "tarsos: bpm=$bpm conf=$confidence beats=${beats.size} onsets=${onsets.size} support=$support chance=$chance agree=$agree",
        )
        return TempoEstimate(bpm.toFloat(), confidence.toFloat(), agree)
    }

    // Onset times are rounded to 10 ms, so the period comes from the whole beat span rather than single gaps.
    private fun period(beats: List<Double>): Double? {
        if (beats.size < 4) return null
        val gaps = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }.sortedArray()
        val median = gaps[gaps.size / 2]
        if (median <= 0.0) return null
        val span = beats.last() - beats.first()
        val count = round(span / median).toLong()
        return if (count < 1L) null else span / count
    }

    // Beat trackers often lock onto half or double time, so the tempo is folded into the usual song range.
    private fun fold(bpm: Double): Double {
        var b = bpm
        while (b > MAX_BPM) b /= 2.0
        while (b < MIN_BPM) b *= 2.0
        return b
    }

    private companion object {
        const val FFT_SIZE = 1024
        const val HOP = 512
        const val BYTES_PER_SAMPLE = 2L
        const val PEAK_THRESHOLD = 0.4
        const val MIN_ONSET_GAP_SEC = 0.05
        const val SILENCE_DB = -70.0
        const val MIN_AUDIO_SEC = 15
        const val MIN_BEATS = 16
        const val MIN_BPM = 70.0
        const val MAX_BPM = 180.0
        const val SUPPORT_TOLERANCE_SEC = 0.06
        const val AGREE_TOLERANCE = 0.03
        const val DISAGREE_CONFIDENCE_FACTOR = 0.8
    }
}
