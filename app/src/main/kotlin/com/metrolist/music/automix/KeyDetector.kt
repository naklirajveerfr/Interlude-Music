package com.metrolist.music.automix

import timber.log.Timber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/**
 * A song's key. [tonic] is 0 for C up to 11 for B. [strength] is how well the song matches that key's profile and
 * [margin] how far it beats the best key that would not mix with it, so a song that sits between neighbouring keys
 * is not [isConfident].
 */
data class KeyEstimate(val tonic: Int, val minor: Boolean, val strength: Float, val margin: Float) {
    val isConfident: Boolean get() = strength >= MIN_STRENGTH && margin >= MIN_MARGIN

    // Number on the Camelot wheel (1..12). Relative major and minor keys share it.
    private val camelotNumber: Int
        get() {
            val majorTonic = if (minor) (tonic + 3) % 12 else tonic
            return (7 + majorTonic * 7 % 12) % 12 + 1
        }

    /** True for the same key, the relative major or minor, or a neighbour a fifth away. */
    fun isCompatibleWith(other: KeyEstimate): Boolean {
        val a = camelotNumber
        val b = other.camelotNumber
        if (a == b) return true
        val step = (a - b + 12) % 12
        return minor == other.minor && (step == 1 || step == 11)
    }

    companion object {
        const val MIN_STRENGTH = 0.35f
        const val MIN_MARGIN = 0.08f
    }
}

/**
 * Estimates a song's key from the pitch classes it spends its time on, using the Krumhansl-Schmuckler key profiles.
 * Feed mono PCM with [push], then read the result with [estimate].
 */
internal class ChromaStream {
    private val fft = Fft(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val buf = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val chroma = DoubleArray(12)
    private var pitchClass = IntArray(0)
    private var lowBin = 0
    private var filled = 0
    private var frames = 0
    private var rate = 0

    fun push(mono: FloatArray, n: Int, sampleRate: Int) {
        if (rate == 0) {
            rate = sampleRate
            val binHz = rate.toDouble() / N
            lowBin = kotlin.math.ceil(LOW_HZ / binHz).toInt()
            val highBin = minOf(N / 2 - 1, kotlin.math.floor(HIGH_HZ / binHz).toInt())
            pitchClass =
                IntArray(highBin - lowBin + 1) {
                    val midi = 69.0 + 12.0 * ln((lowBin + it) * binHz / 440.0) / ln(2.0)
                    round(midi).toInt().mod(12)
                }
        }
        for (i in 0 until n) {
            buf[filled++] = mono[i]
            if (filled == N) {
                frame()
                filled = 0
            }
        }
    }

    private fun frame() {
        for (i in 0 until N) {
            re[i] = buf[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        for (j in pitchClass.indices) {
            val k = lowBin + j
            // The square root of the magnitude keeps loud low notes from drowning out the rest.
            chroma[pitchClass[j]] += sqrt(sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()))
        }
        frames++
    }

    fun estimate(): KeyEstimate? {
        if (frames < MIN_FRAMES) return null
        val candidates = ArrayList<KeyEstimate>(24)
        for (tonic in 0 until 12) {
            val rotated = DoubleArray(12) { chroma[(tonic + it) % 12] }
            candidates.add(KeyEstimate(tonic, false, correlation(rotated, MAJOR_PROFILE).toFloat(), 0f))
            candidates.add(KeyEstimate(tonic, true, correlation(rotated, MINOR_PROFILE).toFloat(), 0f))
        }
        val best = candidates.maxByOrNull { it.strength } ?: return null
        val runnerUp = candidates.filter { !best.isCompatibleWith(it) }.maxOfOrNull { it.strength } ?: return null
        val result = best.copy(margin = best.strength - runnerUp)
        Timber.tag("AutomixDebug").d("key: tonic=${result.tonic} minor=${result.minor} strength=${result.strength} margin=${result.margin}")
        return result
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val meanA = a.average()
        val meanB = b.average()
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        return if (varA <= 0.0 || varB <= 0.0) 0.0 else cov / sqrt(varA * varB)
    }

    private companion object {
        const val N = 4096
        const val LOW_HZ = 200.0
        const val HIGH_HZ = 2000.0
        const val MIN_FRAMES = 80
        val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}
