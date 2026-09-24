// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an audio file to raw PCM, once, when the session arms.
 *
 * Doing this up front rather than at cue time is a deliberate reliability
 * choice. At 04:37 the app should do nothing but hand an already-decoded short
 * array to a fresh AudioTrack: no file I/O, no codec initialisation, no
 * MediaPlayer that has been sitting idle for six hours holding a native codec
 * instance across audio-server restarts and route changes. Decoding is also
 * where a broken or unsupported file gets discovered - at bedtime, when the app
 * can tell the user, rather than silently in the middle of the night.
 */
object PcmDecoder {

    class DecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Anything longer than this is almost certainly not meant to be a cue. */
    private const val MAX_SECONDS = 120

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    fun decode(context: Context, uri: Uri): PcmBuffer {
        // WAV is handled directly: it is what we generate ourselves, and it
        // keeps the critical path free of MediaCodec entirely.
        runCatching {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { WavIo.read(it) }
        }.getOrNull()?.let { return it }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return decodeFrom(extractor)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("Could not decode audio from $uri", e)
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun decode(file: File): PcmBuffer {
        runCatching { WavIo.read(file) }.getOrNull()?.let { return it }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return decodeFrom(extractor)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("Could not decode audio from ${file.name}", e)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeFrom(extractor: MediaExtractor): PcmBuffer {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw DecodeException("File contains no audio track")

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw DecodeException("Audio track has no MIME type")

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
            ?: throw DecodeException("No decoder available for $mime")

        val out = ByteArrayOutputStream()
        val maxBytes = MAX_SECONDS * sampleRate * channels * 2

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder is authoritative, not the container header:
                        // they disagree often enough on real-world files that
                        // trusting the container produces chipmunk playback.
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk)
                            buffer.clear()
                            if (out.size() + chunk.size <= maxBytes) out.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                        if (out.size() >= maxBytes) sawOutputEnd = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val bytes = out.toByteArray()
        if (bytes.isEmpty()) throw DecodeException("Decoder produced no audio")

        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        // More than stereo is possible but pointless for a sleep cue, and
        // AudioTrack channel-mask handling for surround adds real complexity.
        return if (channels > 2) {
            PcmBuffer(downmixToStereo(shorts, channels), sampleRate, 2)
        } else {
            PcmBuffer(shorts, sampleRate, channels)
        }
    }

    private fun downmixToStereo(samples: ShortArray, channels: Int): ShortArray {
        val frames = samples.size / channels
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            var left = 0
            var right = 0
            for (c in 0 until channels) {
                val v = samples[f * channels + c].toInt()
                if (c % 2 == 0) left += v else right += v
            }
            val lCount = (channels + 1) / 2
            val rCount = channels / 2
            out[f * 2] = PcmBuffer.clamp16(left / lCount.coerceAtLeast(1))
            out[f * 2 + 1] = PcmBuffer.clamp16(if (rCount > 0) right / rCount else left / lCount.coerceAtLeast(1))
        }
        return out
    }
}
