package com.eldervision

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class GeminiLiveClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onText(chunk: String)
        fun onInterrupted()
        fun onTurnComplete()
        fun onStatus(message: String)
        fun onToolCall(name: String, args: JSONObject): String
    }

    @Volatile
    var isReady = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var resumeHandle: String? = null
    private var closedByUser = false

    fun connect() {
        closedByUser = false
        isReady = false
        val request = Request.Builder().url("${Config.GEMINI_URL}?key=$apiKey").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                dropped(webSocket, "closed ($code) $reason")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(webSocket, t.message ?: t.javaClass.simpleName)
        })
    }

    fun sendFrame(jpeg: ByteArray): Boolean {
        if (!isReady) return false
        val video = JSONObject()
            .put("mimeType", "image/jpeg")
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        return socket?.send(JSONObject().put("realtimeInput", JSONObject().put("video", video)).toString()) == true
    }

    fun sendUserText(text: String): Boolean {
        if (!isReady) return false
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        val message = JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true),
        )
        return socket?.send(message.toString()) == true
    }

    fun sendParts(parts: JSONArray): Boolean {
        if (!isReady) return false
        val turn = JSONObject().put("role", "user").put("parts", parts)
        val message = JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true),
        )
        return socket?.send(message.toString()) == true
    }

    fun close() {
        closedByUser = true
        isReady = false
        socket?.close(1000, null)
        socket = null
    }

    private fun setupMessage(): JSONObject {
        val resumption = JSONObject()
        resumeHandle?.let { resumption.put("handle", it) }
        val setup = JSONObject()
            .put("model", Config.GEMINI_MODEL)
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Config.SYSTEM_PROMPT))),
            )
            .put("outputAudioTranscription", JSONObject())
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations())))
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", resumption)
        return JSONObject().put("setup", setup)
    }

    private fun toolDeclarations(): JSONArray {
        fun schema(type: String, description: String? = null) =
            JSONObject().put("type", type).also { if (description != null) it.put("description", description) }
        val scanRoom = JSONObject()
            .put("name", "go_to")
            .put("description", "Call when the user asks to walk to a place. The phone follows a route saved in memory for that place, or otherwise scans the room and sends you photos to plan from.")
            .put(
                "parameters",
                schema("OBJECT")
                    .put("properties", JSONObject().put("destination", schema("STRING", "Short name of the place, for example the bedroom door.")))
                    .put("required", JSONArray().put("destination")),
            )
        val leg = schema("OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("heading", schema("NUMBER", "Direction to face, in degrees clockwise from the scan start, using the same labels as the photos."))
                    .put("steps", schema("INTEGER", "Number of normal steps to walk in that direction; one step is about 0.6 m."))
                    .put("note", schema("STRING", "What this leg passes, for example past the ottoman.")),
            )
            .put("required", JSONArray().put("heading").put("steps").put("note"))
        val setPlan = JSONObject()
            .put("name", "set_plan")
            .put("description", "Give the phone the walking path to the destination as a list of legs. The phone then coaches every turn and step.")
            .put(
                "parameters",
                schema("OBJECT")
                    .put("properties", JSONObject().put("legs", schema("ARRAY").put("items", leg)))
                    .put("required", JSONArray().put("legs")),
            )
        val cancel = JSONObject()
            .put("name", "cancel_guidance")
            .put("description", "Stop the current scan or walking guidance, when the user asks to stop or cancel it.")
        val recordRoute = JSONObject()
            .put("name", "record_route")
            .put("description", "Start recording a route while a sighted helper walks the user to a place, when the user asks to remember or record the way to it.")
            .put(
                "parameters",
                schema("OBJECT")
                    .put("properties", JSONObject().put("destination", schema("STRING", "Name of the place the route leads to.")))
                    .put("required", JSONArray().put("destination")),
            )
        val finishRecording = JSONObject()
            .put("name", "finish_recording")
            .put("description", "Save the route being recorded, when the user says they have arrived or to stop recording.")
        return JSONArray().put(scanRoom).put(setPlan).put(cancel).put(recordRoute).put(finishRecording)
    }

    private fun answerToolCall(call: JSONObject) {
        val functionCalls = call.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()
        for (i in 0 until functionCalls.length()) {
            val fc = functionCalls.getJSONObject(i)
            val name = fc.optString("name")
            val result = listener.onToolCall(name, fc.optJSONObject("args") ?: JSONObject())
            responses.put(
                JSONObject().put("id", fc.optString("id")).put("name", name).put("response", JSONObject().put("result", result)),
            )
        }
        socket?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }

    private fun handle(webSocket: WebSocket, raw: String) {
        if (webSocket !== socket) return
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return

        if (message.has("setupComplete")) {
            isReady = true
            listener.onReady()
        }

        message.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable") && handle.isNotEmpty()) resumeHandle = handle
        }

        message.optJSONObject("serverContent")?.let { content ->
            val text = content.optJSONObject("outputTranscription")?.optString("text").orEmpty()
            if (text.isNotEmpty()) listener.onText(text)
            if (content.optBoolean("interrupted")) listener.onInterrupted()
            if (content.optBoolean("turnComplete")) listener.onTurnComplete()
        }

        message.optJSONObject("toolCall")?.let(::answerToolCall)

        if (message.has("goAway")) {
            listener.onStatus("Gemini session rotating")
            main.post { reconnect() }
        }
    }

    private fun reconnect() {
        if (closedByUser) return
        val old = socket
        socket = null
        old?.close(1000, "reconnecting")
        connect()
    }

    private fun dropped(webSocket: WebSocket, reason: String) {
        if (webSocket !== socket) return
        if (!isReady) resumeHandle = null
        isReady = false
        socket = null
        if (closedByUser) return
        listener.onStatus("Gemini disconnected: $reason. Reconnecting.")
        main.postDelayed({ if (!closedByUser && socket == null) connect() }, 1500)
    }
}
