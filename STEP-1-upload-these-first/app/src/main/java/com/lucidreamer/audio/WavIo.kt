// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal 16-bit PCM WAV reader and writer.
 *
 * Exists so the two formats the app produces itself - synthesised tones and
 * text-to-speech output - can be read without going anywhere near MediaCodec.
 * That keeps the most reliability-critical playback path free of codec
 * initialisation, which is one more thing that cannot fail at 4am. Arbitrary
 * user-supplied audio still goes through [PcmDecoder].
 */
object WavIo {

    class MalformedWavException(message: String) : IOException(message)

    fun write(file: File, pcm: PcmBuffer) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            val dataBytes = pcm.samples.size * 2
            val byteRate = pcm.sampleRate * pcm.channels * 2
            val blockAlign = pcm.channels * 2

            out.writeBytes("RIFF")
            out.writeIntLe(36 + dataBytes)
            out.writeBytes("WAVE")

            out.writeBytes("fmt ")
            out.writeIntLe(16)            // PCM fmt chunk size
            out.writeShortLe(1)           // format = PCM
            out.writeShortLe(pcm.channels)
            out.writeIntLe(pcm.sampleRate)
            out.writeIntLe(byteRate)
            out.writeShortLe(blockAlign)
            out.writeShortLe(16)          // bits per sample

            out.writeBytes("data")
            out.writeIntLe(dataBytes)

            val bytes = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm.samples) bytes.putShort(s)
            out.write(bytes.array())
        }
    }

    fun read(file: File): PcmBuffer = file.inputStream().buffered().use { read(it) }

    /**
     * Reads a PCM WAV.
     *
     * Walks the chunk list rather than assuming `fmt ` and `data` sit at fixed
     * offsets - plenty of encoders, including some Android TTS engines, insert
     * LIST/INFO chunks, and a fixed-offset reader would silently produce noise.
     */
    fun read(input: InputStream): PcmBuffer {
        val header = ByteArray(12)
        input.readFullyOrThrow(header, "truncated RIFF header")
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") throw MalformedWavException("not a RIFF/WAVE file")

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var format = 0

        val chunkHeader = ByteArray(8)
        while (true) {
            val read = input.read(chunkHeader)
            if (read < 8) throw MalformedWavException("no data chunk found")
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size)
                    input.readFullyOrThrow(fmt, "truncated fmt chunk")
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    format = bb.short.toInt()
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int   // byte rate, derivable
                    bb.short // block align, derivable
                    bitsPerSample = bb.short.toInt()
                }

                "data" -> {
                    if (format != 1) throw MalformedWavException("only uncompressed PCM is supported (format=$format)")
                    if (bitsPerSample != 16) throw MalformedWavException("only 16-bit PCM is supported (bits=$bitsPerSample)")
                    if (channels !in 1..2) throw MalformedWavException("unsupported channel count $channels")

                    val data = ByteArray(size)
                    input.readFullyOrThrow(data, "truncated data chunk")
                    val shorts = ShortArray(size / 2)
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    return PcmBuffer(shorts, sampleRate, channels)
                }

                else -> {
                    // Unknown chunk: skip it, honouring the RIFF word-alignment rule.
                    var remaining = size.toLong() + (size and 1)
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0) throw MalformedWavException("truncated while skipping '$id'")
                        remaining -= skipped
                    }
                }
            }
        }
    }

    private fun InputStream.readFullyOrThrow(into: ByteArray, message: String) {
        var off = 0
        while (off < into.size) {
            val n = read(into, off, into.size - off)
            if (n < 0) throw MalformedWavException(message)
            off += n
        }
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
