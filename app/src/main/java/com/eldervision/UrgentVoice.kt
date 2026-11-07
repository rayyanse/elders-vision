package com.eldervision

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import android.media.AudioFormat
import android.media.AudioTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class UrgentVoice(context: Context, private val http: OkHttpClient, private val apiKey: String) {
    enum class Phrase(val text: String) {
        STOP("Stop. Something is in front of you."),
        CLEAR("It's clear in front of you now."),
        LEFT("Careful, something close on your left."),
        RIGHT("Careful, something close on your right."),
    }

    private val sampleRate = 24000
    private val cache = ConcurrentHashMap<Phrase, ByteArray>()
    private val worker = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private var track: AudioTrack? = null
    private var fallbackReady = false
    private val fallback = TextToSpeech(context) { status -> fallbackReady = status == TextToSpeech.SUCCESS }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        setSpeechRate(1.2f)
    }

    val ready: Boolean get() = cache.size == Phrase.entries.size

    fun preload() {
        for (phrase in Phrase.entries) if (!cache.containsKey(phrase)) worker.execute { fetch(phrase) }
    }

    fun play(phrase: Phrase): Boolean {
        val pcm = cache[phrase]
        if (pcm == null) {
            worker.execute { fetch(phrase) }
            if (!fallbackReady) return false
            fallback.speak(phrase.text, TextToSpeech.QUEUE_FLUSH, null, phrase.name)
            return true
        }
        player.execute {
            track?.run {
                runCatching { stop() }
                release()
            }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    play()
                }
        }
        return true
    }

    fun close() {
        worker.shutdownNow()
        fallback.shutdown()
        player.execute {
            track?.release()
            track = null
        }
        player.shutdown()
    }

    private fun fetch(phrase: Phrase) {
        val body = JSONObject()
            .put("text", phrase.text)
            .put("sample_rate", sampleRate)
            .put("encoding", "pcm_s16le")
            .put("stream", false)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(Config.TTS_HTTP_URL)
            .header("Authorization", apiKey)
            .post(body)
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    cache[phrase] = response.body!!.bytes()
                } else {
                    Log.w("Iris", "Urgent phrase ${phrase.name} fetch failed: HTTP ${response.code}")
                }
            }
        }.onFailure { Log.w("Iris", "Urgent phrase ${phrase.name} fetch failed: ${it.message}") }
    }
}
