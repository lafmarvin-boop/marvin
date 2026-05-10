package com.marvin.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Synthèse vocale via ElevenLabs.
 *
 * - Nécessite une clé API ElevenLabs (https://elevenlabs.io)
 * - Voix configurable (defaultId = "Adam" multilingue, voix masculine)
 * - Streaming MP3 décodé par MediaCodec puis joué via AudioTrack
 * - Coût ~5 € / mois pour 30 000 caractères (largement assez en perso)
 *
 * Requiert internet — fallback automatique sur Piper TTS si offline ou
 * si la clé est absente (géré par TtsEngineFactory).
 */
class ElevenLabsTtsEngine(
    @Suppress("unused") private val context: Context,
    private val settings: Settings
) : TtsEngine {

    @Volatile private var currentTrack: AudioTrack? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun isReady(): Boolean = settings.elevenLabsApiKey.isNotBlank()

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return@withContext
        val apiKey = settings.elevenLabsApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "speak: pas de clé ElevenLabs")
            return@withContext
        }
        val voiceId = settings.elevenLabsVoiceId.ifBlank { DEFAULT_VOICE_ID }

        // Téléchargement MP3
        val mp3Bytes = try { fetchMp3(apiKey, voiceId, cleaned) } catch (t: Throwable) {
            Log.e(TAG, "fetchMp3 failed", t)
            null
        } ?: return@withContext
        Log.i(TAG, "ElevenLabs MP3 reçu : ${mp3Bytes.size / 1024} Ko")

        // Décodage + lecture
        playMp3(mp3Bytes)
    }

    override fun stop() {
        currentTrack?.runCatching { stop() }
    }

    override fun release() {
        stop()
    }

    private fun fetchMp3(apiKey: String, voiceId: String, text: String): ByteArray? {
        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
            })
        }
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .header("accept", "audio/mpeg")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "ElevenLabs ${resp.code}: ${resp.body?.string()?.take(200)}")
                return@use null
            }
            resp.body?.bytes()
        }
    }

    private suspend fun playMp3(mp3: ByteArray) {
        // Écris le MP3 dans un fichier temp, parce que MediaExtractor
        // demande un FileDescriptor / String path.
        val tmp = File.createTempFile("eleven_", ".mp3", context.cacheDir)
        try {
            FileOutputStream(tmp).use { it.write(mp3) }
            decodeAndPlay(tmp.absolutePath)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun decodeAndPlay(path: String) {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) {
            Log.e(TAG, "Pas de piste audio dans le MP3")
            extractor.release(); return
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
            else AudioFormat.CHANNEL_OUT_STEREO

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val pcmFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        currentTrack = track
        track.play()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm, 0, info.size)
                        outBuf.clear()
                        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* OK */ }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(5)
                }
            }
            // Attend la fin du buffer audio
            while (track.playbackHeadPosition * channels < info.presentationTimeUs * sampleRate / 1_000_000 &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                delay(20)
            }
        } finally {
            currentTrack = null
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val TAG = "ElevenLabsTts"
        // Voix par défaut : "Adam" — voix masculine multilingue, qualité top
        private const val DEFAULT_VOICE_ID = "pNInz6obpgDQGcFmaJgB"
    }
}

@Suppress("unused")
private fun ByteBuffer.use(block: (ByteBuffer) -> Unit) { block(this) }
