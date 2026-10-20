package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Mutes the lead vocal of a stereo song while leaving the rest of the mix alone.
 *
 * Lead vocals are almost always panned dead centre, so the centre (mid) signal is the part that carries them.
 * Only the vocal band of that mid signal is subtracted from both channels; the bass and kick below
 * [LOW_CUT_HZ], the hi-hats and air above [HIGH_CUT_HZ] and everything panned off-centre stay as they were.
 * This is phase cancellation, not stem separation, so it also takes some centre-panned snare and synth
 * with it and leaves a little vocal reverb behind.
 *
 * Turning [muted] on or off ramps over [RAMP_MS] so the change never clicks.
 */
@UnstableApi
class VocalMuteAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var muted = false

    private var encoding = C.ENCODING_INVALID
    private var rampStep = 0f

    // 0 leaves the audio untouched, 1 removes the whole vocal band.
    private var amount = 0f

    private var lowCoef = 0f
    private var highCoef = 0f
    private var low1 = 0f
    private var low2 = 0f
    private var high1 = 0f
    private var high2 = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported =
            inputAudioFormat.channelCount == 2 &&
                (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        // Anything else (mono, 24/32-bit) passes through untouched.
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET

        encoding = inputAudioFormat.encoding
        val rate = inputAudioFormat.sampleRate.toFloat()
        lowCoef = onePoleCoefficient(LOW_CUT_HZ, rate)
        highCoef = onePoleCoefficient(HIGH_CUT_HZ, rate)
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
                val band = nextBand((l + r) * 0.5f, target)
                output.putShort((l - band).coerceIn(-32768f, 32767f).toInt().toShort())
                output.putShort((r - band).coerceIn(-32768f, 32767f).toInt().toShort())
            }
        } else {
            repeat(remaining / 8) {
                val l = inputBuffer.getFloat()
                val r = inputBuffer.getFloat()
                val band = nextBand((l + r) * 0.5f, target)
                output.putFloat((l - band).coerceIn(-1f, 1f))
                output.putFloat((r - band).coerceIn(-1f, 1f))
            }
        }
        output.flip()
    }

    // The vocal band of the mid signal, scaled by the current ramp position.
    private fun nextBand(mid: Float, target: Float): Float {
        amount =
            when {
                amount < target -> minOf(target, amount + rampStep)
                amount > target -> maxOf(target, amount - rampStep)
                else -> amount
            }
        low1 += lowCoef * (mid - low1)
        low2 += lowCoef * (low1 - low2)
        high1 += highCoef * (mid - high1)
        high2 += highCoef * (high1 - high2)
        return (high2 - low2) * amount
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        low1 = 0f
        low2 = 0f
        high1 = 0f
        high2 = 0f
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        amount = 0f
        low1 = 0f
        low2 = 0f
        high1 = 0f
        high2 = 0f
    }

    private fun onePoleCoefficient(cutoffHz: Float, sampleRate: Float): Float =
        1f - exp(-2.0 * Math.PI * cutoffHz / sampleRate).toFloat()

    private companion object {
        const val LOW_CUT_HZ = 150f
        const val HIGH_CUT_HZ = 7000f
        const val RAMP_MS = 120f
    }
}
