package com.eldervision

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlin.concurrent.thread

class AssemblyAiStt(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onFinalTranscript(text: String)
        fun onStatus(message: String)
    }

    @Volatile
    private var running = false
    private var lastTurnOrder = -1

    @Volatile
    private var socket: WebSocket? = null

    private val main = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null

    fun start() {
        if (running) return
        running = true
        connect()
        startMicrophone()
    }

    fun stop() {
        running = false
        socket?.send("""{"type":"Terminate"}""")
        socket?.close(1000, null)
        socket = null
        recorder?.run {
            runCatching { stop() }
            release()
        }
        recorder = null
    }

    private fun connect() {
        lastTurnOrder = -1
        val request = Request.Builder()
            .url(Config.STT_URL)
            .header("Authorization", apiKey)
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (message.optString("type") != "Turn" || !message.optBoolean("end_of_turn")) return
                val order = message.optInt("turn_order", -1)
                if (order >= 0 && order == lastTurnOrder) return
                lastTurnOrder = order
                val transcript = message.optString("transcript").trim()
                if (transcript.isNotEmpty()) listener.onFinalTranscript(transcript)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                dropped(webSocket, "closed ($code) $reason")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(webSocket, t.message ?: t.javaClass.simpleName)
        })
    }

    private fun dropped(webSocket: WebSocket, reason: String) {
        if (webSocket !== socket || !running) return
        socket = null
        listener.onStatus("Speech recognition disconnected: $reason. Reconnecting.")
        main.postDelayed({ if (running && socket == null) connect() }, 2000)
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophone() {
        val sampleRate = 16000
        val chunkBytes = sampleRate / 10 * 2
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val mic = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunkBytes * 4),
        )
        recorder = mic
        mic.startRecording()

        thread(name = "stt-microphone", isDaemon = true) {
            val buffer = ByteArray(chunkBytes)
            while (running) {
                var filled = 0
                while (filled < chunkBytes && running) {
                    val read = mic.read(buffer, filled, chunkBytes - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < chunkBytes) {
                    Thread.sleep(50)
                    continue
                }
                socket?.send(buffer.toByteString(0, chunkBytes))
            }
        }
    }
}
