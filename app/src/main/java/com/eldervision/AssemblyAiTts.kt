package com.eldervision

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AssemblyAiTts(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val onStatus: (String) -> Unit,
) {
    private val sampleRate = 24000
    private val main = Handler(Looper.getMainLooper())
    private val player = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val pendingCancels = AtomicInteger()

    @Volatile
    private var running = false

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var playbackEndsAt = 0L

    private var track: AudioTrack? = null

    private val keepAlive = object : Runnable {
        override fun run() {
            if (!running) return
            socket?.send("""{"type":"KeepAlive"}""")
            main.postDelayed(this, 60_000)
        }
    }

    val isSpeaking: Boolean
        get() = SystemClock.elapsedRealtime() < playbackEndsAt + 300

    fun start() {
        if (running) return
        running = true
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        connect()
        main.postDelayed(keepAlive, 60_000)
    }

    fun speak(text: String) {
        socket?.send(JSONObject().put("type", "Generate").put("text", text).toString())
        socket?.send("""{"type":"Flush"}""")
    }

    fun stopSpeaking() {
        generation.incrementAndGet()
        playbackEndsAt = 0
        if (socket?.send("""{"type":"Cancel"}""") == true) pendingCancels.incrementAndGet()
        track?.run {
            runCatching {
                pause()
                flush()
            }
        }
        player.execute {
            track?.run {
                flush()
                play()
            }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(keepAlive)
        socket?.close(1000, null)
        socket = null
        player.execute {
            track?.run {
                runCatching { stop() }
                release()
            }
            track = null
        }
        player.shutdown()
    }

    private fun connect() {
        pendingCancels.set(0)
        val request = Request.Builder()
            .url(Config.TTS_URL)
            .header("Authorization", apiKey)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (message.optString("type")) {
                    "Audio" -> if (pendingCancels.get() == 0) play(Base64.decode(message.optString("audio"), Base64.DEFAULT))
                    "Cancelled" -> pendingCancels.updateAndGet { maxOf(0, it - 1) }
                    "Error" -> onStatus("Speech output error: ${message.optString("error")}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                dropped(webSocket, "closed ($code) $reason")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(webSocket, t.message ?: t.javaClass.simpleName)
        })
    }

    private fun play(pcm: ByteArray) {
        val durationMs = pcm.size * 1000L / (sampleRate * 2)
        val now = SystemClock.elapsedRealtime()
        playbackEndsAt = maxOf(now, playbackEndsAt) + durationMs
        val chunkGeneration = generation.get()
        player.execute {
            if (chunkGeneration == generation.get()) track?.write(pcm, 0, pcm.size)
        }
    }

    private fun dropped(webSocket: WebSocket, reason: String) {
        if (webSocket !== socket || !running) return
        socket = null
        onStatus("Speech output disconnected: $reason. Reconnecting.")
        main.postDelayed({ if (running && socket == null) connect() }, 2000)
    }
}
