/**
 * Music Recognition Feature
 *
 * This feature is based on the original MusicRecognizer project by Aleksey Saenko.
 * Original project: https://github.com/aleksey-saenko/MusicRecognizer
 *
 * Special thanks to Aleksey Saenko for the music recognition implementation.
 */

package com.metrolist.music.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.metrolist.shazamkit.Shazam
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Service for recognizing music using audio fingerprinting.
 * Records audio from the microphone, generates a Shazam-compatible fingerprint,
 * and sends it to the Shazam API for recognition.
 */
object MusicRecognitionService {

    // Recording parameters
    private const val RECORDING_SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    // Recording duration: 12 seconds for better recognition accuracy
    // We use 12s directly to match the fallback duration for maximum compatibility
    private const val RECORDING_DURATION_MS = 12000L
    private const val TAG = "MusicRecognitionService"

    // Continuous listening: try the last few seconds of audio again and again until a match.
    private const val CONTINUOUS_MIN_WINDOW_MS = 5000L
    private const val CONTINUOUS_MAX_WINDOW_MS = 12000L
    private const val CONTINUOUS_ATTEMPT_INTERVAL_MS = 3000L
    private const val CONTINUOUS_TIMEOUT_MS = 45000L
    private const val CONTINUOUS_MAX_CONSECUTIVE_ERRORS = 3
    private const val BYTES_PER_MS = RECORDING_SAMPLE_RATE * 2 / 1000.0

    private val _recognitionStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val recognitionStatus: StateFlow<RecognitionStatus> = _recognitionStatus.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)

    /** Live microphone loudness in 0..1 while listening, used to animate the listening screen. */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /**
     * Set to true by the widget service after it has already persisted the result to the
     * database, so that [RecognitionScreen] skips the duplicate insert.
     * Reset to false by [reset].
     */
    var resultSavedExternally: Boolean = false

    @Volatile
    private var recordingStartElapsedMs: Long = 0L

    @Volatile
    private var playbackPending = false

    // Bumped by reset() so a running continuous session notices it was cancelled and stops.
    @Volatile
    private var runToken = 0

    /**
     * Returns true once per successful recognition, so the screen starts the synced playback
     * only a single time even if it is recomposed or recreated.
     */
    fun takePendingPlayback(): Boolean {
        val pending = playbackPending
        playbackPending = false
        return pending
    }

    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start the music recognition process.
     * Records audio, generates fingerprint, and queries Shazam API.
     *
     * With [continuous] the microphone stays open and the latest audio is re-tried every few
     * seconds, finishing at the first match instead of after one fixed recording.
     */
    @SuppressLint("MissingPermission")
    suspend fun recognize(
        context: Context,
        continuous: Boolean = false,
    ): RecognitionStatus = withContext(Dispatchers.IO) {
        if (!hasRecordPermission(context)) {
            Timber.tag(TAG).w("Microphone permission not granted, aborting recognition")
            return@withContext RecognitionStatus.Error("Microphone permission not granted")
        }

        _recognitionStatus.value = RecognitionStatus.Listening
        Timber.tag(TAG).d("Starting music recognition (continuous=%s)", continuous)

        try {
            if (continuous) {
                return@withContext recognizeContinuously()
            }

            // Step 1: Record audio
            val audioData = recordAudio()
            Timber.tag(TAG).d("Audio recorded: %d bytes", audioData.size)

            _recognitionStatus.value = RecognitionStatus.Processing

            // Steps 2-4: resample, fingerprint and query Shazam
            identify(audioData).fold(
                onSuccess = { recognitionResult ->
                    Timber.tag(TAG).i(
                        "Recognition successful: '%s' by %s (matchOffsetMs=%s)",
                        recognitionResult.title,
                        recognitionResult.artist,
                        recognitionResult.matchOffsetMs,
                    )
                    publishSuccess(recognitionResult, recordingStartElapsedMs)
                },
                onFailure = { error ->
                    Timber.tag(TAG).w(error, "Recognition API returned failure: %s", error.message)
                    _recognitionStatus.value = failureStatus(error)
                }
            )

            _recognitionStatus.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Recognition failed with exception")
            _recognitionStatus.value = RecognitionStatus.Error(e.message ?: "Recognition failed")
            _recognitionStatus.value
        }
    }

    private fun publishSuccess(
        result: RecognitionResult,
        sampleStartElapsedMs: Long,
    ) {
        playbackPending = true
        _recognitionStatus.value =
            RecognitionStatus.Success(result.copy(sampleStartElapsedMs = sampleStartElapsedMs))
    }

    private fun failureStatus(error: Throwable): RecognitionStatus {
        val message = error.message ?: "Unknown error"
        return if (message.contains("No match", ignoreCase = true)) {
            RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
        } else {
            RecognitionStatus.Error(message)
        }
    }

    /** Turns raw 44.1 kHz mono PCM into a fingerprint and asks Shazam what it is. */
    private suspend fun identify(audioData: ByteArray): Result<RecognitionResult> {
        return try {
            // Convert to mono if needed and resample to 16kHz
            Timber.tag(TAG).d("Resampling audio from %dHz to %dHz", RECORDING_SAMPLE_RATE, VibraSignature.REQUIRED_SAMPLE_RATE)
            val decodedAudio = DecodedAudio(
                data = audioData,
                channelCount = 1,
                sampleRate = RECORDING_SAMPLE_RATE,
                pcmEncoding = AUDIO_FORMAT
            )

            val resampledAudio = AudioResampler.resample(
                decodedAudio,
                VibraSignature.REQUIRED_SAMPLE_RATE
            ).getOrElse { error ->
                Timber.tag(TAG).e(error, "Audio resampling failed")
                return Result.failure(Exception("Failed to resample audio: ${error.message}"))
            }
            Timber.tag(TAG).d("Resampled audio: %d bytes, %d channels, %dHz", resampledAudio.data.size, resampledAudio.channelCount, resampledAudio.sampleRate)

            // Verify format
            require(
                resampledAudio.channelCount == 1 &&
                resampledAudio.sampleRate == VibraSignature.REQUIRED_SAMPLE_RATE &&
                resampledAudio.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT &&
                ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN &&
                resampledAudio.data.isNotEmpty() &&
                resampledAudio.data.size % 2 == 0
            ) { "Invalid audio format for fingerprint generation" }

            // Generate fingerprint using native library
            val signature = try {
                VibraSignature.fromI16(resampledAudio.data)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Fingerprint generation failed")
                return Result.failure(Exception("Failed to generate fingerprint: ${e.message}"))
            }

            // Send to Shazam API
            val sampleDurationMs = (resampledAudio.data.size / 2) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE
            Timber.tag(TAG).d("Fingerprint generated, sampleDurationMs=%d", sampleDurationMs)

            Shazam.recognize(signature, sampleDurationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Keeps the microphone open and, once enough audio is buffered, re-tries the most recent
     * window every few seconds (one request at a time) until Shazam finds a match.
     * Each attempt remembers when its own window started so synced playback stays accurate.
     */
    @SuppressLint("MissingPermission")
    private suspend fun recognizeContinuously(): RecognitionStatus = coroutineScope {
        val token = ++runToken
        val bufferSize = AudioRecord.getMinBufferSize(RECORDING_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )

        val ring = ByteArray((CONTINUOUS_MAX_WINDOW_MS * BYTES_PER_MS).toInt() and 1.inv())
        var writePos = 0
        var totalBytes = 0L
        val readBuffer = ByteArray(bufferSize)

        val attemptRunning = AtomicBoolean(false)
        val consecutiveErrors = AtomicInteger(0)
        val lastError = AtomicReference<Throwable?>(null)
        val winner = AtomicReference<RecognitionResult?>(null)
        val winnerStartElapsedMs = AtomicLong(0L)

        var recordStartElapsedMs = 0L
        var lastAttemptAtMs = 0L

        try {
            audioRecord.startRecording()
            recordStartElapsedMs = SystemClock.elapsedRealtime()
            Timber.tag(TAG).d("Continuous listening started")

            while (isActive &&
                token == runToken &&
                winner.get() == null &&
                consecutiveErrors.get() < CONTINUOUS_MAX_CONSECUTIVE_ERRORS
            ) {
                val bytesRead = audioRecord.read(readBuffer, 0, bufferSize)
                if (bytesRead <= 0) continue
                publishLevel(readBuffer, bytesRead)

                var offset = 0
                while (offset < bytesRead) {
                    val chunk = minOf(bytesRead - offset, ring.size - writePos)
                    System.arraycopy(readBuffer, offset, ring, writePos, chunk)
                    writePos = (writePos + chunk) % ring.size
                    offset += chunk
                }
                totalBytes += bytesRead

                val elapsedMs = SystemClock.elapsedRealtime() - recordStartElapsedMs
                if (elapsedMs >= CONTINUOUS_TIMEOUT_MS) break

                val bufferedMs = (minOf(totalBytes, ring.size.toLong()) / BYTES_PER_MS).toLong()
                val attemptDue = elapsedMs - lastAttemptAtMs >= CONTINUOUS_ATTEMPT_INTERVAL_MS
                if (bufferedMs >= CONTINUOUS_MIN_WINDOW_MS && attemptDue && attemptRunning.compareAndSet(false, true)) {
                    lastAttemptAtMs = elapsedMs

                    val windowBytes = minOf(totalBytes, ring.size.toLong()).toInt() and 1.inv()
                    val window = ByteArray(windowBytes)
                    val start = ((writePos - windowBytes) % ring.size + ring.size) % ring.size
                    val firstPart = minOf(windowBytes, ring.size - start)
                    System.arraycopy(ring, start, window, 0, firstPart)
                    System.arraycopy(ring, 0, window, firstPart, windowBytes - firstPart)

                    val windowStartAudioMs = ((totalBytes - windowBytes) / BYTES_PER_MS).toLong()
                    val windowStartElapsedMs = recordStartElapsedMs + windowStartAudioMs

                    launch {
                        try {
                            identify(window).fold(
                                onSuccess = { found ->
                                    consecutiveErrors.set(0)
                                    if (token == runToken && winner.compareAndSet(null, found)) {
                                        winnerStartElapsedMs.set(windowStartElapsedMs)
                                    }
                                },
                                onFailure = { error ->
                                    if (error.message?.contains("No match", ignoreCase = true) == true) {
                                        consecutiveErrors.set(0)
                                    } else {
                                        lastError.set(error)
                                        consecutiveErrors.incrementAndGet()
                                    }
                                },
                            )
                        } finally {
                            attemptRunning.set(false)
                        }
                    }
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            _audioLevel.value = 0f
            this.coroutineContext.cancelChildren()
        }

        ensureActive()

        val found = winner.get()
        when {
            token != runToken -> _recognitionStatus.value
            found != null -> {
                Timber.tag(TAG).i(
                    "Continuous recognition successful: '%s' by %s (matchOffsetMs=%s)",
                    found.title,
                    found.artist,
                    found.matchOffsetMs,
                )
                publishSuccess(found, winnerStartElapsedMs.get())
                _recognitionStatus.value
            }
            consecutiveErrors.get() >= CONTINUOUS_MAX_CONSECUTIVE_ERRORS -> {
                _recognitionStatus.value = RecognitionStatus.Error(lastError.get()?.message ?: "Recognition failed")
                _recognitionStatus.value
            }
            else -> {
                _recognitionStatus.value =
                    RecognitionStatus.NoMatch("Couldn't find a match. Try getting closer to the music.")
                _recognitionStatus.value
            }
        }
    }

    private fun publishLevel(
        buffer: ByteArray,
        bytesRead: Int,
    ) {
        val sampleCount = bytesRead / 2
        if (sampleCount == 0) return
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample
            i += 2
        }
        val rms = sqrt(sumSquares / sampleCount) / 32768.0
        _audioLevel.value = (rms * 8.0).coerceIn(0.0, 1.0).toFloat()
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): ByteArray = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        Timber.tag(TAG).d("Recording audio: sampleRate=%d, bufferSize=%d, durationMs=%d", RECORDING_SAMPLE_RATE, bufferSize, RECORDING_DURATION_MS)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        val startTime = System.currentTimeMillis()

        try {
            audioRecord.startRecording()
            recordingStartElapsedMs = SystemClock.elapsedRealtime()
            Timber.tag(TAG).d("AudioRecord started, recording for %dms", RECORDING_DURATION_MS)

            while (System.currentTimeMillis() - startTime < RECORDING_DURATION_MS && isActive) {
                val bytesRead = audioRecord.read(buffer, 0, bufferSize)
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                    publishLevel(buffer, bytesRead)
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
            _audioLevel.value = 0f
        }

        val totalBytes = outputStream.size()
        Timber.tag(TAG).d("Audio recording complete: %d bytes collected", totalBytes)
        outputStream.toByteArray()
    }

    fun reset() {
        Timber.tag(TAG).d("Recognition state reset")
        runToken++
        _recognitionStatus.value = RecognitionStatus.Ready
        _audioLevel.value = 0f
        resultSavedExternally = false
        playbackPending = false
    }
}
