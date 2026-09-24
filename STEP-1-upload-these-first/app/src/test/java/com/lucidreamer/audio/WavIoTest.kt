// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import com.lucidreamer.domain.cue.BuiltInTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavIoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `round trip preserves samples, rate and channel count`() {
        val original = PcmBuffer(ShortArray(1000) { (it * 31 % 20000 - 10000).toShort() }, 22050, 1)
        val f = tmp.newFile("mono.wav")
        WavIo.write(f, original)
        assertEquals(original, WavIo.read(f))
    }

    @Test
    fun `round trip works for stereo`() {
        val original = PcmBuffer(ShortArray(800) { (it % 9000).toShort() }, 44100, 2)
        val f = tmp.newFile("stereo.wav")
        WavIo.write(f, original)
        assertEquals(original, WavIo.read(f))
    }

    @Test
    fun `a synthesised tone survives a round trip byte for byte`() {
        val bell = ToneSynth.generate(BuiltInTone.SOFT_BELL)
        val f = tmp.newFile("bell.wav")
        WavIo.write(f, bell)
        assertEquals(bell, WavIo.read(f))
    }

    @Test
    fun `unknown chunks between fmt and data are skipped rather than parsed as audio`() {
        // Some TTS engines emit a LIST/INFO chunk here. A fixed-offset reader
        // would read it as samples and produce noise.
        val pcm = PcmBuffer(shortArrayOf(100, -100, 200, -200), 8000, 1)
        val bytes = wavWithExtraChunk(pcm, "LIST", byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(pcm, WavIo.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `an odd-sized unknown chunk is skipped with its RIFF pad byte`() {
        val pcm = PcmBuffer(shortArrayOf(7, 8, 9, 10), 8000, 1)
        val bytes = wavWithExtraChunk(pcm, "junk", byteArrayOf(1, 2, 3))
        assertEquals(pcm, WavIo.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `a non-wav file is rejected clearly`() {
        val junk = ByteArray(64) { it.toByte() }
        assertThrows(WavIo.MalformedWavException::class.java) {
            WavIo.read(ByteArrayInputStream(junk))
        }
    }

    @Test
    fun `a truncated file is rejected rather than returning partial audio`() {
        val pcm = PcmBuffer(ShortArray(500) { 1000 }, 8000, 1)
        val f = tmp.newFile("cut.wav")
        WavIo.write(f, pcm)
        val truncated = f.readBytes().copyOfRange(0, 60)
        assertThrows(WavIo.MalformedWavException::class.java) {
            WavIo.read(ByteArrayInputStream(truncated))
        }
    }

    /** Builds a valid WAV with an extra chunk inserted between `fmt ` and `data`. */
    private fun wavWithExtraChunk(pcm: PcmBuffer, chunkId: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun short(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())

        val dataBytes = pcm.samples.size * 2
        val padded = payload.size + (payload.size and 1)

        ascii("RIFF"); int(36 + padded + 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); int(16); short(1); short(pcm.channels); int(pcm.sampleRate)
        int(pcm.sampleRate * pcm.channels * 2); short(pcm.channels * 2); short(16)
        ascii(chunkId); int(payload.size); out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
        ascii("data"); int(dataBytes)
        val bb = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm.samples) bb.putShort(s)
        out.write(bb.array())
        return out.toByteArray()
    }
}
