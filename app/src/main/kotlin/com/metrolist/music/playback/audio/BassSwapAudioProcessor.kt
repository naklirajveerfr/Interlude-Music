package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp

/**
 * Scales the bass of a song (the part below roughly [CUTOFF_HZ]) between none and all of it, so two songs mixing
 * together can trade their basslines instead of stacking them. Passes the audio through untouched while the bass
 * gain is 1. Only 16-bit and float PCM are filtered; other formats leave the processor inactive.
 */
@UnstableApi
class BassSwapAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var targetGain = 1f

    @Volatile
    private var snapToTarget = false

    private var gain = 1f
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var lowPassCoef = 0f
    private var gainSmoothCoef = 0f
    private var stage1 = FloatArray(0)
    private var stage2 = FloatArray(0)

    /** Sets how much of the bass is kept, 0 to 1. The change is smoothed unless [immediate] is true. */
    fun setBassGain(value: Float, immediate: Boolean = false) {
        targetGain = value.coerceIn(0f, 1f)
        if (immediate) snapToTarget = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate.toDouble()
        lowPassCoef = (1.0 - exp(-2.0 * PI * CUTOFF_HZ / rate)).toFloat()
        gainSmoothCoef = (1.0 - exp(-1.0 / (GAIN_SMOOTH_SEC * rate))).toFloat()
        stage1 = FloatArray(channels)
        stage2 = FloatArray(channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        if (snapToTarget) {
            gain = targetGain
            snapToTarget = false
        }
        if (gain == 1f && targetGain == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val frameSize = channels * if (isFloat) 4 else 2
        val target = targetGain
        while (inputBuffer.remaining() >= frameSize) {
            gain += gainSmoothCoef * (target - gain)
            val removed = 1f - gain
            for (c in 0 until channels) {
                val x = if (isFloat) inputBuffer.getFloat() else inputBuffer.getShort() / 32768f
                // Two cascaded one-pole high-pass stages (12 dB per octave); the gain blends them with the original.
                stage1[c] += lowPassCoef * (x - stage1[c])
                val high1 = x - stage1[c]
                stage2[c] += lowPassCoef * (high1 - stage2[c])
                val high2 = high1 - stage2[c]
                val y = x - removed * (x - high2)
                if (isFloat) {
                    output.putFloat(y)
                } else {
                    output.putShort((y * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
                }
            }
        }
        output.put(inputBuffer)
        output.flip()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channels = 0
        gain = targetGain
    }

    private companion object {
        const val CUTOFF_HZ = 250.0
        const val GAIN_SMOOTH_SEC = 0.03
    }
}
