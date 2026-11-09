/*
 * Choona - Guitar Tuner
 * Copyright (C) 2026 Rohan Khayech
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.rohankhayech.choona.lib.controller.tuner

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import com.rohankhayech.choona.lib.model.error.TunerException
import java.io.IOException

/**
 * Microphone input stream for the audio dispatcher that runs the tuner.
 *
 * Replaces `AudioDispatcherFactory.fromDefaultMicrophone` with three differences, all of them meant
 * for a tuner reading a quiet signal such as an electric guitar played without an amplifier: it
 * asks the device for an audio source the manufacturer did not process, it reports the failures
 * that prevent the tuner from starting as a [TunerException], and its [read] never throws, because
 * `AudioDispatcher.run` turns any `IOException` raised there into an `Error` on the audio thread,
 * which takes the whole process down.
 *
 * ### Source cascade
 * The sources are tried in order and the first one that starts recording is kept:
 *
 * 1. [MediaRecorder.AudioSource.UNPROCESSED], the raw signal of the microphone.
 * 2. [MediaRecorder.AudioSource.VOICE_RECOGNITION], the compromise for devices without an
 *    unprocessed source: it turns the automatic gain control and the noise suppression off on most
 *    devices, while still allowing the echo cancellation they need for calls.
 * 3. [MediaRecorder.AudioSource.MIC], the source of the factory this class replaces.
 *
 * `MIC` is the last resort because on many devices it enables the automatic gain control and the
 * noise reduction of the manufacturer. Both are tuned for speech: with a faint signal they raise
 * the noise floor between the plucks, and the reduction eats the quiet tail of the decay, so the
 * envelope of the note ends up deformed exactly where the tuner reads it. `UNPROCESSED` is
 * therefore the preferred source, and `VOICE_RECOGNITION` the one that most devices accept when the
 * unprocessed source does not exist.
 *
 * A source is only accepted once [AudioRecord] is initialised **and** recording, since some devices
 * initialize the unprocessed source and then refuse to start it.
 *
 * The cascade itself is [AudioSourceCascade], which holds the policy alone and is therefore tested
 * on the host JVM: this class only opens the sources it returns, in the order it returns them.
 *
 * ### Buffering
 * The buffer of the [AudioRecord] is the smallest the device accepts, enlarged to hold at least two
 * analysis windows so that the hardware buffer does not starve the audio thread between reads. It
 * only changes how much audio is captured before it is read: the window the detector analyses is
 * the one the audio dispatcher asks for, and it is never altered here.
 *
 * The stream is owned by the dispatch loop: [be.tarsos.dsp.AudioDispatcher.stop] is what closes it.
 * [close] is idempotent and can be called from another thread while the audio thread is blocked
 * reading.
 *
 * @param sampleRateHz Sample rate of the capture in Hz.
 * @param windowSamples Number of samples the detector analyses per frame, used to size the buffer
 *                      of the [AudioRecord].
 *
 * @throws TunerException If the device cannot capture mono 16 bit audio at [sampleRateHz], or if
 *                        none of the sources of the cascade can start recording.
 *
 * @author Rohan Khayech
 */
internal class TunerAudioSource(
    sampleRateHz: Int = 44100,
    windowSamples: Int = 4096,
) : TarsosDSPAudioInputStream {

    /** Microphone the samples are read from. Released only by [close]. */
    private val record: AudioRecord =
        startRecording(sampleRateHz, recordBufferBytes(sampleRateHz, windowSamples))

    /** Format of the captured audio: mono, 16 bits, signed, little endian. */
    private val format = TarsosDSPAudioFormat(sampleRateHz.toFloat(), 16, 1, true, false)

    /** Whether the microphone is released. Set before the [AudioRecord] is touched, on any thread. */
    @Volatile
    private var released = false

    /**
     * Reads up to [len] bytes of audio into [b] starting at [off], blocking until the request is
     * complete, the stream ends or the microphone fails.
     *
     * A failure of the microphone is reported as the end of the stream instead of being thrown, so
     * that the dispatcher finishes its loop and the tuner keeps running.
     *
     * The method only ever returns the whole block or `-1`, never a partial read: a total shorter
     * than [len] makes `be.tarsos.dsp.AudioDispatcher.readNextAudioBlock` throw an `IOException`,
     * which its loop turns into a `java.lang.Error` on the audio thread.
     *
     * A read that returns no data is retried after a short pause, up to [MAX_EMPTY_READS] times,
     * after which the stream is reported as over: a microphone that keeps returning no data has
     * stopped delivering audio, and spinning on it would burn the battery of the whole capture.
     *
     * @param b Buffer the samples are copied into.
     * @param off Index of [b] where the first sample is written.
     * @param len Maximum number of bytes to read.
     * @return The number of bytes read, `0` if [len] is not positive, or `-1` if the stream is over.
     */
    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (released) return -1
        if (len <= 0) return 0

        var total = 0
        var emptyReads = 0
        while (total < len) {
            val read = try {
                record.read(b, off + total, len - total)
            } catch (e: IllegalStateException) {
                -1
            } catch (e: IllegalArgumentException) {
                -1
            }
            when {
                read > 0 -> {
                    total += read
                    emptyReads = 0
                }
                read < 0 -> return -1
                // A released stream cannot complete the block, and a partial total is an error for
                // the dispatcher: report the end of the stream instead.
                released -> return -1
                else -> {
                    emptyReads++
                    if (emptyReads > MAX_EMPTY_READS || !pauseBeforeRetry()) return -1
                }
            }
        }
        return total
    }

    /** Not supported, as in the stream of TarsosDSP. */
    @Throws(IOException::class)
    override fun skip(bytesToSkip: Long): Long {
        throw IOException("Can not skip in audio stream")
    }

    /**
     * Stops and releases the microphone. Called by [be.tarsos.dsp.AudioDispatcher.stop], never by
     * the tuner, and safe to call more than once and from another thread than the one reading.
     */
    override fun close() {
        if (released) return
        released = true
        releaseQuietly(record)
    }

    /** Returns the format of the captured audio. */
    override fun getFormat(): TarsosDSPAudioFormat {
        return format
    }

    /** Returns `-1`, since the length of a live stream is unknown. */
    override fun getFrameLength(): Long {
        return -1
    }

    /**
     * Waits before retrying a read that returned no data.
     *
     * The read of an [AudioRecord] is supposed to block until the buffer holds something, but
     * devices that deliver nothing, or that were released behind the back of the stream, return
     * immediately: a thread that spins on those reads burns the battery of the whole capture, so
     * the retry sleeps instead, and the caller gives up after [MAX_EMPTY_READS] of them.
     *
     * @return Whether the wait completed, `false` if the thread was interrupted and the stream must
     * therefore end.
     */
    private fun pauseBeforeRetry(): Boolean = try {
        Thread.sleep(RETRY_SLEEP_MS)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    companion object {

        /** Bytes per sample of the 16 bit mono capture. */
        private const val BYTES_PER_SAMPLE = 2

        /** Number of analysis windows the buffer of the [AudioRecord] holds at least. */
        private const val BUFFER_WINDOWS = 2

        /**
         * Reads that may return no data before the stream is taken as over.
         *
         * A block of 4096 samples is filled every 93 ms, so a hundred empty reads of a healthy
         * device cannot happen: this many means the microphone is gone.
         */
        private const val MAX_EMPTY_READS = 100

        /** Wait between two reads that returned no data, in milliseconds. */
        private const val RETRY_SLEEP_MS = 1L

        /** Size of the buffer of the [AudioRecord] in bytes, never smaller than two windows. */
        private fun recordBufferBytes(sampleRateHz: Int, windowSamples: Int): Int {
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes < 0) {
                throw TunerException(
                    "Cannot capture $sampleRateHz Hz mono 16 bit audio: " +
                        "AudioRecord.getMinBufferSize returned $minBytes",
                    null,
                )
            }
            return maxOf(minBytes, windowSamples * BYTES_PER_SAMPLE * BUFFER_WINDOWS)
        }

        /** Name of an audio source of the cascade, used to report the ones that were tried. */
        private fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> source.toString()
        }

        /**
         * Opens the first source of the cascade that starts recording.
         *
         * The cascade itself is the one [AudioSourceCascade] defines, in the order it returns: the
         * sources are tried one after the other and the first one that is both initialised and
         * recording is kept.
         *
         * @throws TunerException If no source of the cascade can capture audio on this device.
         */
        private fun startRecording(sampleRateHz: Int, recordBufferBytes: Int): AudioRecord {
            val tried = mutableListOf<String>()
            for (source in AudioSourceCascade.candidates(Build.VERSION.SDK_INT)) {
                val record = openRecording(source, sampleRateHz, recordBufferBytes)
                if (record != null) return record
                tried.add(sourceName(source))
            }
            throw TunerException(
                "Cannot capture $sampleRateHz Hz audio, tried the sources: ${tried.joinToString()}",
                null,
            )
        }

        /**
         * Opens and starts the given audio source, releasing whatever it manages to allocate when
         * the source cannot be used.
         *
         * @return The recording microphone, or `null` if the source cannot capture audio.
         */
        private fun openRecording(
            source: Int,
            sampleRateHz: Int,
            recordBufferBytes: Int,
        ): AudioRecord? {
            val record = try {
                AudioRecord(
                    source,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferBytes,
                )
            } catch (e: IllegalArgumentException) {
                return null
            } catch (e: SecurityException) {
                return null
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                releaseQuietly(record)
                return null
            }

            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                releaseQuietly(record)
                return null
            }

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseQuietly(record)
                return null
            }
            return record
        }

        /** Frees a microphone that could not be used, ignoring the failures of an incomplete one. */
        private fun releaseQuietly(record: AudioRecord) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }
}
