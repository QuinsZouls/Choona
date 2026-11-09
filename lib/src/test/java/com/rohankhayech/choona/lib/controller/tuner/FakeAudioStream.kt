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

import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream

/**
 * A [TarsosDSPAudioInputStream] that stands in for the microphone of the device, so that the audio
 * chain of the tuner and the lifecycle of [Tuner.start]/[Tuner.stop] can be exercised on the host
 * JVM, where there is no `AudioRecord` to capture from.
 *
 * The samples are served the way the capture delivers them —mono, signed and little endian, which
 * is the format [getFormat] reports so that the dispatcher converts them on its own— and the stream
 * is closed by the dispatcher when the audio is over or when it is stopped, as the production
 * stream is.
 *
 * Two modes, which are the two cases the tests need:
 *
 * - [finite] serves a fixed signal and reports the end of the audio right after the last sample, so
 *   that [be.tarsos.dsp.AudioDispatcher.run] returns on the calling thread, with no extra thread
 *   involved.
 * - [silentUntilClosed] serves blocks of silence until it is closed, and reports the end of the
 *   audio afterwards. It is the stream of a live capture, which only ends because it is stopped, and
 *   it is the only way to observe whether the tuner really stops the dispatcher and releases the
 *   stream.
 *
 * Reads may be shorter than the block that was asked for, and the last read of a [finite] stream
 * usually is: `AudioDispatcher.readNextAudioBlock` keeps calling [read] until the block is full, so a
 * short read costs another call and nothing else. What it cannot survive is a `0`, which leaves the
 * dispatcher asking for the same bytes forever on the audio thread, so this stream only answers a
 * positive count or `-1`, which is what it reports once the signal is over or it has been closed.
 *
 * Like the production stream, [close] is idempotent: the flag and the counter only move on the first
 * close, so a stream that the dispatcher closed on its own and that is closed again by another owner
 * still reports one close instead of hiding the first.
 *
 * @param pcm Signal the stream serves, or `null` to serve silence until it is closed.
 * @param sampleRateHz Sample rate of the format the stream reports, in Hz.
 *
 * @author Rohan Khayech
 */
internal class FakeAudioStream private constructor(
    private val pcm: ShortArray?,
    sampleRateHz: Int,
) : TarsosDSPAudioInputStream {

    /** Format of the served audio: mono, 16 bits, signed, little endian. */
    private val format = TarsosDSPAudioFormat(
        sampleRateHz.toFloat(),
        BITS_PER_SAMPLE,
        MONO_CHANNELS,
        true,
        false,
    )

    /** Number of bytes served so far, which is where the finite signal resumes. */
    private var offsetBytes = 0

    /** Whether the stream is closed, which in production is what releases the microphone. */
    @Volatile
    var closed: Boolean = false
        private set

    /** Number of times this stream went from open to closed. */
    @Volatile
    var closeCount: Int = 0
        private set

    /** Number of blocks of audio served so far. */
    @Volatile
    var blocksRead: Int = 0
        private set

    /**
     * Thread that served the last block, which is the audio thread of the dispatcher.
     *
     * It is what tells a test that the dispatcher finished: once the tuner has stopped it, the
     * thread that was reading no longer runs.
     */
    @Volatile
    var reader: Thread? = null
        private set

    /**
     * Reads up to [len] bytes of audio into [b] starting at [off].
     *
     * @param b Buffer the samples are copied into.
     * @param off Index of [b] where the first sample is written.
     * @param len Maximum number of bytes to read.
     * @return The number of bytes read, `0` if [len] is not positive, or `-1` if the stream is
     *         closed or the finite signal is over.
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (len <= 0) return 0

        val samples = pcm
        val count = if (samples == null) serveSilence(b, off, len) else copySignal(samples, b, off, len)
        if (count <= 0) return -1

        reader = Thread.currentThread()
        blocksRead++
        return count
    }

    /** Not used by the dispatcher of the tuner, which never skips into the stream. */
    override fun skip(bytesToSkip: Long): Long = 0L

    /** Closes the stream, which ends the audio of [read]. Safe to call more than once. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeCount++
    }

    /** Returns the format of the served audio. */
    override fun getFormat(): TarsosDSPAudioFormat = format

    /** Returns `-1`, since the length of a capture is unknown. */
    override fun getFrameLength(): Long = -1

    /**
     * Serves a block of silence, at the pace of a capture instead of as fast as the audio thread can
     * ask for it.
     *
     * The wait is what keeps a stream that nobody closes —the failure the lifecycle tests are meant
     * to catch— from spinning at full speed on the analysis chain for the rest of the run.
     *
     * @return The number of bytes served, or `-1` if the stream was closed while waiting.
     */
    private fun serveSilence(b: ByteArray, off: Int, len: Int): Int {
        Thread.sleep(BLOCK_INTERVAL_MS)
        if (closed) return -1
        b.fill(0, off, off + len)
        return len
    }

    /**
     * Copies the next [len] bytes of the finite signal into [b].
     *
     * @return The number of bytes copied, or `-1` if the signal is over.
     */
    private fun copySignal(pcm: ShortArray, b: ByteArray, off: Int, len: Int): Int {
        val totalBytes = pcm.size * BYTES_PER_SAMPLE
        if (offsetBytes >= totalBytes) return -1

        val count = minOf(len, totalBytes - offsetBytes)
        for (i in 0 until count) {
            val position = offsetBytes + i
            val sample = pcm[position / BYTES_PER_SAMPLE].toInt()
            b[off + i] = if (position % BYTES_PER_SAMPLE == 0) {
                (sample and BYTE_MASK).toByte()
            } else {
                (sample shr BITS_PER_BYTE and BYTE_MASK).toByte()
            }
        }
        offsetBytes += count
        return count
    }

    companion object {

        /** Bytes of one PCM16 sample. */
        private const val BYTES_PER_SAMPLE = 2

        /** Bits of one byte, and of one PCM16 sample. */
        private const val BITS_PER_BYTE = 8

        /** Bits of one PCM16 sample. */
        private const val BITS_PER_SAMPLE = BITS_PER_BYTE * BYTES_PER_SAMPLE

        /** Mask of the lowest byte of a sample. */
        private const val BYTE_MASK = 0xFF

        /** The capture is mono. */
        private const val MONO_CHANNELS = 1

        /**
         * Wait between two blocks of silence, in milliseconds.
         *
         * A block of [TunerPipeline.BUFFER_OVERLAP] samples lasts 46 ms of real audio, so this is
         * several times faster than a capture: the tests only need the stream not to spin.
         */
        private const val BLOCK_INTERVAL_MS = 2L

        /**
         * Creates a stream that serves [samples] as PCM16 and then reports the end of the audio.
         *
         * @param samples Signal to serve, at [sampleRateHz].
         * @param sampleRateHz Sample rate of the signal, in Hz.
         */
        fun finite(
            samples: ShortArray,
            sampleRateHz: Int = TunerPipeline.SAMPLE_RATE,
        ): FakeAudioStream = FakeAudioStream(samples, sampleRateHz)

        /**
         * Creates a stream that serves blocks of silence until it is closed, like a live capture
         * that only ends because the tuner stops it.
         *
         * @param sampleRateHz Sample rate of the format the stream reports, in Hz.
         */
        fun silentUntilClosed(
            sampleRateHz: Int = TunerPipeline.SAMPLE_RATE,
        ): FakeAudioStream = FakeAudioStream(null, sampleRateHz)
    }
}
