// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.lucidreamer.domain.cue.CueSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Owns every audio asset a cue might play, and turns a [CueSound] into PCM.
 *
 * ## Everything is resolved when the session arms, not when the cue fires
 *
 * Decoding a file, rendering speech or reading from storage at 04:37 gives
 * several new ways for the night to fail silently. Doing it at bedtime means a
 * missing file, an unsupported format or an absent speech engine is discovered
 * while the user is awake and can be told about it.
 *
 * ## Why speech is rendered to a file
 *
 * A text-to-speech engine that has been idle for six hours has to cold-start,
 * and on a Doze-suspended phone that regularly means the utterance is simply
 * never spoken. Rendering to a WAV up front turns an unreliable IPC round trip
 * into an ordinary buffer of samples.
 */
class CueAssetStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "cues")
    private val importedDir: File get() = File(root, "imported")
    private val recordingsDir: File get() = File(root, "recordings")
    private val ttsDir: File get() = File(root, "tts")

    /** Decoded audio for the current session. Cleared when the session ends. */
    private val cache = ConcurrentHashMap<String, PcmBuffer>()

    fun fileFor(relativePath: String): File = File(root, relativePath)

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    sealed interface Resolution {
        data class Ready(val pcm: PcmBuffer) : Resolution
        data object SilentByDesign : Resolution
        data class Failed(val reason: String) : Resolution
    }

    suspend fun resolve(sound: CueSound): Resolution = withContext(Dispatchers.IO) {
        val key = cacheKey(sound)
        cache[key]?.let { return@withContext Resolution.Ready(it) }

        val result = when (sound) {
            is CueSound.Silent -> Resolution.SilentByDesign

            is CueSound.BuiltIn -> Resolution.Ready(ToneSynth.generate(sound.tone))

            is CueSound.File -> decodeStored(sound.relativePath, sound.displayName)

            is CueSound.Recording -> decodeStored(sound.relativePath, sound.displayName)

            is CueSound.Speech -> renderSpeech(sound)
        }

        if (result is Resolution.Ready) cache[key] = result.pcm
        result
    }

    private fun decodeStored(relativePath: String, displayName: String): Resolution {
        val file = fileFor(relativePath)
        if (!file.exists()) {
            return Resolution.Failed("\"$displayName\" is missing - it may have been deleted")
        }
        return runCatching { Resolution.Ready(PcmDecoder.decode(file)) }
            .getOrElse { Resolution.Failed("Could not decode \"$displayName\": ${it.message}") }
    }

    private fun cacheKey(sound: CueSound): String = when (sound) {
        is CueSound.BuiltIn -> "builtin:${sound.tone}"
        is CueSound.File -> "file:${sound.relativePath}"
        is CueSound.Recording -> "rec:${sound.relativePath}"
        is CueSound.Speech -> "tts:${speechHash(sound)}"
        is CueSound.Silent -> "silent"
    }

    fun clearCache() = cache.clear()

    // -----------------------------------------------------------------------
    // Importing
    // -----------------------------------------------------------------------

    /**
     * Copies a user-chosen file into app storage.
     *
     * Copying rather than holding a content URI is deliberate: a URI can stop
     * resolving because the file moved, the SD card was unmounted or the
     * granting app was updated. Finding that out at 4am is not acceptable, and
     * a cue sound is small enough that the duplication costs nothing.
     */
    suspend fun importFile(uri: Uri, displayName: String): Result<CueSound.File> =
        withContext(Dispatchers.IO) {
            runCatching {
                importedDir.mkdirs()
                val ext = displayName.substringAfterLast('.', "audio").take(8)
                val target = File(importedDir, "${UUID.randomUUID()}.$ext")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: error("Could not open the selected file")

                // Decode immediately so an unsupported format is reported now,
                // while the user is looking at the screen.
                PcmDecoder.decode(target)

                CueSound.File(
                    relativePath = "imported/${target.name}",
                    displayName = displayName.substringBeforeLast('.').ifBlank { "Imported sound" },
                )
            }.onFailure { importedDir.listFiles()?.forEach { f -> if (f.length() == 0L) f.delete() } }
        }

    fun newRecordingFile(): Pair<File, String> {
        recordingsDir.mkdirs()
        val file = File(recordingsDir, "${UUID.randomUUID()}.m4a")
        return file to "recordings/${file.name}"
    }

    fun deleteAsset(relativePath: String): Boolean = runCatching { fileFor(relativePath).delete() }.getOrDefault(false)

    // -----------------------------------------------------------------------
    // Text to speech
    // -----------------------------------------------------------------------

    private fun speechHash(sound: CueSound.Speech): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${sound.text}|${sound.voiceId ?: ""}".toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Whether a usable speech engine exists, so setup can warn instead of failing at night. */
    suspend fun speechAvailable(): Boolean = withContext(Dispatchers.Main) {
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                tts = TextToSpeech(context) { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    runCatching { tts?.shutdown() }
                    if (cont.isActive) cont.resume(ok)
                }
                cont.invokeOnCancellation { runCatching { tts.shutdown() } }
            }
        } ?: false
    }

    private suspend fun renderSpeech(sound: CueSound.Speech): Resolution {
        val cached = File(ttsDir, "${speechHash(sound)}.wav")
        if (cached.exists()) {
            return runCatching { Resolution.Ready(WavIo.read(cached)) }
                .getOrElse { Resolution.Failed("Could not read rendered speech: ${it.message}") }
        }

        ttsDir.mkdirs()
        val rendered = synthesiseToFile(sound.text, cached)
        if (!rendered) {
            cached.delete()
            return Resolution.Failed(
                "Could not render speech. Check that a text-to-speech engine is installed and has a voice downloaded.",
            )
        }

        return runCatching { Resolution.Ready(WavIo.read(cached)) }
            .getOrElse { Resolution.Failed("Speech engine produced a file the app could not read: ${it.message}") }
    }

    private suspend fun synthesiseToFile(text: String, target: File): Boolean =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    val utteranceId = UUID.randomUUID().toString()

                    fun finish(ok: Boolean) {
                        runCatching { tts?.shutdown() }
                        if (cont.isActive) cont.resume(ok)
                    }

                    tts = TextToSpeech(context) { status ->
                        if (status != TextToSpeech.SUCCESS) {
                            finish(false)
                            return@TextToSpeech
                        }
                        val engine = tts ?: return@TextToSpeech
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) = Unit
                            override fun onDone(id: String?) = finish(true)

                            @Deprecated("Required override; the int variant replaces it")
                            override fun onError(id: String?) = finish(false)
                            override fun onError(id: String?, errorCode: Int) = finish(false)
                        })

                        val result = runCatching {
                            engine.synthesizeToFile(text, null, target, utteranceId)
                        }.getOrDefault(TextToSpeech.ERROR)

                        if (result != TextToSpeech.SUCCESS) finish(false)
                    }

                    cont.invokeOnCancellation { runCatching { tts.shutdown() } }
                }
            } ?: false
        }

    /** Total bytes used by cue assets, for the storage figure in settings. */
    fun storageBytes(): Long =
        runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}
