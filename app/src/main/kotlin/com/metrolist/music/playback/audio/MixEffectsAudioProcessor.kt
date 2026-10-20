package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/**
 * Effects for one side of a smooth skip, in the order the signal passes them:
 * 1. a low-pass sweep ([lowPass]) that closes the highs of a song coming in,
 * 2. a high-pass sweep ([highPass]) that thins out a song going away,
 * 3. an echo: [echoSend] feeds the delay line, [echoReturn] is how much of it is heard, and [dry] is how much of
 *    the song itself is heard, so the song can be cut while its echo keeps ringing.
 *
 * Every control is smoothed so changes never click. Audio passes through untouched while [active] is false. Only
 * 16-bit and float PCM are processed; other formats leave the processor inactive.
 */
@UnstableApi
class MixEffectsAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var active = false

    /** 0 leaves the highs alone, 1 closes them down to a dull low end. */
    @Volatile
    var lowPass = 0f

    /** 0 leaves the lows alone, 1 removes everything but the highs. */
    @Volatile
    var highPass = 0f

    @Volatile
    var dry = 1f

    @Volatile
    var echoSend = 0f

    @Volatile
    var echoReturn = 0f

    @Volatile
    var echoDelaySec = DEFAULT_ECHO_DELAY_SEC

    @Volatile
    private var snapToTarget = false

    @Volatile
    private var clearEcho = false

    /** Jumps the smoothed controls straight to their targets and empties the echo. */
    fun snap() {
        snapToTarget = true
        clearEcho = true
    }

    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var smoothCoef = 0f

    private var lowPassS = 0f
    private var highPassS = 0f
    private var dryS = 1f
    private var sendS = 0f
    private var returnS = 0f

    private var lowPassCoef = 0f
    private var highPassCoef = 0f
    private var coefCountdown = 0

    private var lp1 = FloatArray(0)
    private var lp2 = FloatArray(0)
    private var hp1 = FloatArray(0)
    private var hp2 = FloatArray(0)

    private var echo = FloatArray(0)
    private var echoFrames = 0
    private var echoPos = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        smoothCoef = (1.0 - exp(-1.0 / (SMOOTH_SEC * sampleRate))).toFloat()
        lp1 = FloatArray(channels)
        lp2 = FloatArray(channels)
        hp1 = FloatArray(channels)
        hp2 = FloatArray(channels)
        echoFrames = (sampleRate * MAX_ECHO_SEC).toInt()
        echo = FloatArray(echoFrames * channels)
        echoPos = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        if (snapToTarget) {
            lowPassS = lowPass
            highPassS = highPass
            dryS = dry
            sendS = echoSend
            returnS = echoReturn
            snapToTarget = false
        }
        if (clearEcho) {
            echo.fill(0f)
            clearEcho = false
        }
        if (!active) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val frameSize = channels * if (isFloat) 4 else 2
        val delayFrames = (echoDelaySec * sampleRate).toInt().coerceIn(1, echoFrames - 1)
        while (inputBuffer.remaining() >= frameSize) {
            lowPassS += smoothCoef * (lowPass - lowPassS)
            highPassS += smoothCoef * (highPass - highPassS)
            dryS += smoothCoef * (dry - dryS)
            sendS += smoothCoef * (echoSend - sendS)
            returnS += smoothCoef * (echoReturn - returnS)
            if (coefCountdown-- <= 0) {
                coefCountdown = COEF_INTERVAL_FRAMES
                lowPassCoef = coefficient(LOW_PASS_OPEN_HZ * (LOW_PASS_CLOSED_HZ / LOW_PASS_OPEN_HZ).pow(lowPassS))
                highPassCoef = coefficient(HIGH_PASS_OPEN_HZ * (HIGH_PASS_CLOSED_HZ / HIGH_PASS_OPEN_HZ).pow(highPassS))
            }
            val readPos = (echoPos - delayFrames + echoFrames) % echoFrames
            for (c in 0 until channels) {
                val x = if (isFloat) inputBuffer.getFloat() else inputBuffer.getShort() / 32768f
                lp1[c] += lowPassCoef * (x - lp1[c])
                lp2[c] += lowPassCoef * (lp1[c] - lp2[c])
                val low = x + lowPassS * (lp2[c] - x)
                hp1[c] += highPassCoef * (low - hp1[c])
                hp2[c] += highPassCoef * (hp1[c] - hp2[c])
                val shaped = low - highPassS * hp2[c]

                val delayed = echo[readPos * channels + c]
                echo[echoPos * channels + c] = shaped * sendS + delayed * ECHO_FEEDBACK
                val y = shaped * dryS + delayed * returnS
                if (isFloat) {
                    output.putFloat(y)
                } else {
                    output.putShort((y * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
                }
            }
            echoPos = (echoPos + 1) % echoFrames
        }
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        lp1.fill(0f)
        lp2.fill(0f)
        hp1.fill(0f)
        hp2.fill(0f)
        echo.fill(0f)
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channels = 0
    }

    private fun coefficient(cutoffHz: Float): Float = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()

    companion object {
        const val DEFAULT_ECHO_DELAY_SEC = 0.4f
        private const val MAX_ECHO_SEC = 1.0f
        private const val ECHO_FEEDBACK = 0.5f
        private const val SMOOTH_SEC = 0.03
        private const val COEF_INTERVAL_FRAMES = 32
        private const val LOW_PASS_OPEN_HZ = 18_000f
        private const val LOW_PASS_CLOSED_HZ = 500f
        private const val HIGH_PASS_OPEN_HZ = 30f
        private const val HIGH_PASS_CLOSED_HZ = 1_800f
    }
}
