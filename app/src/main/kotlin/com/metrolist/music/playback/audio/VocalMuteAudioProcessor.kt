package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Mutes the vocals of a stereo song and leaves the beat.
 *
 * Two stages, both only while [muted]:
 * 1. Lead vocals are almost always panned dead centre, so the vocal band ([LOW_CUT_HZ] to [HIGH_CUT_HZ]) of the
 *    centre (mid) signal is subtracted from both channels.
 * 2. What is left of the vocal (backing vocals, doubles, reverb) sits off-centre, so the core vocal range
 *    ([CORE_LOW_HZ] to [CORE_HIGH_HZ]) is also cut on each channel by [CORE_CUT].
 *
 * The kick and bass below the band and the hi-hats above it are untouched; the melodic mids of the song dip too.
 * This is filtering, not stem separation, so it is not a perfect vocal removal.
 *
 * Turning [muted] on or off ramps over [RAMP_MS] so the change never clicks.
 */
@UnstableApi
class VocalMuteAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var muted = false

    /** 0 to 1: how much of the full mute is applied while [muted]. */
    @Volatile
    var strength = 1f

    private var encoding = C.ENCODING_INVALID
    private var rampStep = 0f

    // 0 leaves the audio untouched, 1 applies the full mute.
    private var amount = 0f

    private val mid = BandFilter()
    private val left = BandFilter()
    private val right = BandFilter()

    private var midLow = 0f
    private var midHigh = 0f
    private var coreLow = 0f
    private var coreHigh = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported =
            inputAudioFormat.channelCount == 2 &&
                (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        // Anything else (mono, 24/32-bit) passes through untouched.
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET

        encoding = inputAudioFormat.encoding
        val rate = inputAudioFormat.sampleRate.toFloat()
        midLow = onePoleCoefficient(LOW_CUT_HZ, rate)
        midHigh = onePoleCoefficient(HIGH_CUT_HZ, rate)
        coreLow = onePoleCoefficient(CORE_LOW_HZ, rate)
        coreHigh = onePoleCoefficient(CORE_HIGH_HZ, rate)
        rampStep = 1f / (rate * RAMP_MS / 1000f)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)

        if (!muted && amount == 0f) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val target = if (muted) 1f else 0f

        if (encoding == C.ENCODING_PCM_16BIT) {
            repeat(remaining / 4) {
                val l = inputBuffer.getShort().toFloat()
                val r = inputBuffer.getShort().toFloat()
                output.putShort(process(l, r, target).coerceIn(-32768f, 32767f).toInt().toShort())
                output.putShort(lastRight.coerceIn(-32768f, 32767f).toInt().toShort())
            }
        } else {
            repeat(remaining / 8) {
                val l = inputBuffer.getFloat()
                val r = inputBuffer.getFloat()
                output.putFloat(process(l, r, target).coerceIn(-1f, 1f))
                output.putFloat(lastRight.coerceIn(-1f, 1f))
            }
        }
        output.flip()
    }

    private var lastRight = 0f

    // Returns the left sample and leaves the right one in lastRight.
    private fun process(
        l: Float,
        r: Float,
        target: Float,
    ): Float {
        amount =
            when {
                amount < target -> minOf(target, amount + rampStep)
                amount > target -> maxOf(target, amount - rampStep)
                else -> amount
            }
        val applied = amount * strength
        val centre = mid.band((l + r) * 0.5f, midLow, midHigh) * applied
        val l1 = l - centre
        val r1 = r - centre
        lastRight = r1 - right.band(r1, coreLow, coreHigh) * CORE_CUT * applied
        return l1 - left.band(l1, coreLow, coreHigh) * CORE_CUT * applied
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        mid.reset()
        left.reset()
        right.reset()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        amount = 0f
        mid.reset()
        left.reset()
        right.reset()
    }

    private fun onePoleCoefficient(
        cutoffHz: Float,
        sampleRate: Float,
    ): Float = 1f - exp(-2.0 * Math.PI * cutoffHz / sampleRate).toFloat()

    // Band between two cutoffs: the difference of two cascaded one-pole low-passes.
    private class BandFilter {
        private var low1 = 0f
        private var low2 = 0f
        private var high1 = 0f
        private var high2 = 0f

        fun band(
            x: Float,
            lowCoef: Float,
            highCoef: Float,
        ): Float {
            low1 += lowCoef * (x - low1)
            low2 += lowCoef * (low1 - low2)
            high1 += highCoef * (x - high1)
            high2 += highCoef * (high1 - high2)
            return high2 - low2
        }

        fun reset() {
            low1 = 0f
            low2 = 0f
            high1 = 0f
            high2 = 0f
        }
    }

    private companion object {
        const val LOW_CUT_HZ = 150f
        const val HIGH_CUT_HZ = 7000f
        const val CORE_LOW_HZ = 300f
        const val CORE_HIGH_HZ = 3400f
        const val CORE_CUT = 0.85f
        const val RAMP_MS = 120f
    }
}
