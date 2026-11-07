package com.eldervision

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SafetyState(
    val reading: ObstacleReading? = null,
    val preview: Bitmap? = null,
    val backend: String = "",
    val headingDeg: Float = 0f,
    val pitchDownDeg: Float = 0f,
    val steps: Int = 0,
    val walking: Boolean = false,
    val urgentReady: Boolean = false,
)

data class UiState(
    val status: String = "Starting",
    val navigationOn: Boolean = true,
    val heard: String = "",
    val said: String = "",
    val missingKeys: Boolean = false,
)

private const val TAG = "Iris"

class AssistantViewModel(application: Application) : AndroidViewModel(application), GeminiLiveClient.Listener {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _safety = MutableStateFlow(SafetyState())
    val safety: StateFlow<SafetyState> = _safety

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val gemini = GeminiLiveClient(http, BuildConfig.GEMINI_API_KEY, this)
    private val tts = AssemblyAiTts(http, BuildConfig.ASSEMBLYAI_API_KEY, ::setStatus)
    private val urgentVoice = UrgentVoice(application, http, BuildConfig.ASSEMBLYAI_API_KEY)
    private val beeper = Beeper()
    private val motion = MotionTracker(application)
    private val guide = RoomGuide(motion, ::guideSay, ::sendScan, ::requestReplan)
    private val stt = AssemblyAiStt(http, BuildConfig.ASSEMBLYAI_API_KEY, object : AssemblyAiStt.Listener {
        override fun onFinalTranscript(text: String) {
            if (text.none { it in 'a'..'z' || it in 'A'..'Z' }) {
                Log.i(TAG, "Ignored non-English: $text")
                return
            }
            if (isEcho(text)) {
                Log.i(TAG, "Ignored echo: $text")
                return
            }
            if (words(text).joinToString(" ") in Config.ACKNOWLEDGEMENTS) {
                Log.i(TAG, "Ignored acknowledgement: $text")
                return
            }
            Log.i(TAG, "Heard: $text")
            _state.update { it.copy(heard = text) }
            lastUserSpokeAt = SystemClock.elapsedRealtime()
            ask(text, interrupt = true)
        }

        override fun onStatus(message: String) = setStatus(message)
    })

    private val pendingSpeech = StringBuilder()
    private val turnText = StringBuilder()
    private var started = false
    private var welcomed = false
    private var ignoreNextTurnComplete = false
    private var depthGuard: DepthGuard? = null
    private var objectGuard: ObjectGuard? = null
    private var verticalFovDeg = Config.DEFAULT_VERTICAL_FOV_DEG

    @Volatile
    private var latestReading: ObstacleReading? = null

    @Volatile
    private var lastUrgentAt = 0L
    private val recentlySpoken = ArrayDeque<Pair<Long, String>>()

    @Volatile
    private var lastUserSpokeAt = 0L

    @Volatile
    private var lastWalkingAt = 0L
    private var blockedSince = 0L
    private var clearFrames = 0

    @Volatile
    private var turnSilenced = false
    private var blockedNoticeGiven = false
    private var firstChunkPending = false
    private var askedAt = 0L

    private val centerHistory = ArrayDeque<Pair<Long, Float>>()
    private var badTiltSince = 0L
    private var lastHoldUpAt = 0L

    @Volatile
    private var modelBusy = false

    @Volatile
    private var busySince = 0L

    @Volatile
    private var lastTurnEndedAt = 0L

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var navigationOn = true

    fun start() {
        if (started) return
        started = true
        motion.start()
        beeper.start()
        viewModelScope.launch {
            while (true) {
                _safety.update {
                    it.copy(
                        headingDeg = motion.headingDeg,
                        pitchDownDeg = motion.pitchDownDeg,
                        steps = motion.steps,
                        walking = motion.isWalking,
                        urgentReady = urgentVoice.ready,
                    )
                }
                delay(200)
            }
        }
        if (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.ASSEMBLYAI_API_KEY.isBlank()) {
            _state.update { it.copy(missingKeys = true, status = "Safety only: add API keys to local.properties") }
            return
        }
        viewModelScope.launch {
            while (!urgentVoice.ready) {
                urgentVoice.preload()
                delay(10_000)
            }
        }
        tts.start()
        stt.start()
        gemini.connect()
        setStatus("Connecting to Gemini")
        viewModelScope.launch {
            while (true) {
                if (tts.isSpeaking) lastSpeechHeardAt = SystemClock.elapsedRealtime()
                checkHolding()
                guide.tick()
                maybeRequestNavigationUpdate()
                delay(100)
            }
        }
    }

    fun setVerticalFov(degrees: Double) {
        verticalFovDeg = degrees
        depthGuard?.verticalFovDeg = degrees
    }

    fun onFrame(frame: Bitmap) {
        val guard = depthGuard ?: runCatching { DepthGuard(getApplication()) }
            .onSuccess { guard ->
                guard.verticalFovDeg = verticalFovDeg
                depthGuard = guard
                _safety.update { it.copy(backend = guard.backend) }
            }
            .onFailure { setStatus("Depth model failed: ${it.message}") }
            .getOrNull() ?: return
        val detector = objectGuard ?: runCatching { ObjectGuard(getApplication()) }
            .onSuccess { objectGuard = it }
            .onFailure { setStatus("Object detector failed: ${it.message}") }
            .getOrNull()
        val objects = detector?.let { runCatching { it.detect(frame) }.getOrDefault(emptyList()) }.orEmpty()
        guard.analyze(frame, motion.pitchDownDeg, motion.hasOrientation, objects)?.let(::onReading)

        guide.onFrame(frame, latestReading)
        val now = SystemClock.elapsedRealtime()
        if (gemini.isReady && now - lastFrameAt >= Config.FRAME_INTERVAL_MS) {
            if (gemini.sendFrame(frame.toJpeg(Config.FRAME_MAX_EDGE, Config.JPEG_QUALITY))) lastFrameAt = now
        }
    }

    fun interrupt() {
        tts.stopSpeaking()
    }

    fun toggleNavigation() {
        navigationOn = !navigationOn
        _state.update { it.copy(navigationOn = navigationOn) }
        tts.stopSpeaking()
        tts.speak(if (navigationOn) "Navigation resumed." else "Navigation paused. I'm still listening.")
    }

    fun readText() {
        tts.stopSpeaking()
        tts.speak("Reading.")
        lastFrameAt = 0
        viewModelScope.launch {
            delay(400)
            ask(Config.READ_PROMPT, interrupt = false)
        }
    }

    override fun onReady() {
        setStatus("Live")
        if (welcomed) return
        welcomed = true
        remember(Config.WELCOME)
        tts.speak(Config.WELCOME)
        lastTurnEndedAt = SystemClock.elapsedRealtime() + 4000
    }

    override fun onText(chunk: String) {
        synchronized(this) {
            turnText.append(chunk)
            if (firstChunkPending) {
                firstChunkPending = false
                Log.i(TAG, "Gemini first text after ${SystemClock.elapsedRealtime() - askedAt} ms")
            }
            if (turnSilenced) return
            pendingSpeech.append(chunk)
            speakCompleteSentences(final = false)
        }
    }

    override fun onInterrupted() {
        synchronized(this) { ignoreNextTurnComplete = true }
    }

    override fun onTurnComplete() {
        synchronized(this) {
            if (ignoreNextTurnComplete) {
                ignoreNextTurnComplete = false
                return
            }
            if (!turnSilenced) speakCompleteSentences(final = true)
            val said = turnText.toString().trim()
            if (said.isNotEmpty() && !said.contains(Config.NO_CHANGE)) _state.update { it.copy(said = said) }
            modelBusy = false
            lastTurnEndedAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onStatus(message: String) = setStatus(message)

    override fun onToolCall(name: String, args: JSONObject): String {
        Log.i(TAG, "Tool call $name $args")
        when (name) {
            "scan_room" -> {
                guide.startScan(args.optString("destination").ifBlank { "destination" })
                return "Scanning now. Wait for the photos and do not give directions."
            }
            "set_plan" -> {
                val raw = args.optJSONArray("legs") ?: return "No legs given."
                val legs = (0 until raw.length()).mapNotNull { i ->
                    val leg = raw.optJSONObject(i) ?: return@mapNotNull null
                    Leg(leg.optDouble("heading", 0.0).toFloat(), leg.optInt("steps", 0).coerceIn(1, 15), leg.optString("note"))
                }
                if (legs.isEmpty()) return "No usable legs."
                guide.setPlan(legs)
                return "Plan saved. The phone is now coaching every turn and step; do not give directions yourself."
            }
            "cancel_guidance" -> {
                guide.cancel()
                return "Guidance cancelled."
            }
        }
        return "Unknown function."
    }

    private fun guideSay(text: String) {
        if (text.startsWith("Stop")) {
            synchronized(this) {
                turnSilenced = true
                pendingSpeech.clear()
            }
            tts.stopSpeaking()
        }
        remember(text)
        tts.speak(text)
    }

    private fun sendScan(destination: String, shots: List<ScanShot>) {
        val parts = JSONArray().put(
            JSONObject().put("text", "Room scan for walking to: $destination. Photos follow, each labelled with its heading in degrees clockwise from the scan start."),
        )
        for (shot in shots) {
            parts.put(JSONObject().put("text", "Photo at heading ${shot.headingDeg} degrees, depth sensor ${shot.ahead}:"))
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(shot.jpeg, Base64.NO_WRAP)),
                ),
            )
        }
        parts.put(
            JSONObject().put(
                "text",
                "The user now faces heading ${guide.relativeToScanStart()} degrees. Plan the path to $destination and call set_plan.",
            ),
        )
        synchronized(this) {
            turnSilenced = false
            pendingSpeech.clear()
            turnText.clear()
            firstChunkPending = true
            val sent = gemini.sendParts(parts)
            Log.i(TAG, "Scan sent: ${shots.size} photos, sent=$sent")
            modelBusy = true
            busySince = SystemClock.elapsedRealtime()
            askedAt = busySince
        }
    }

    private fun requestReplan(destination: String) {
        val ahead = latestReading?.center ?: Float.POSITIVE_INFINITY
        ask(
            "Path blocked: something is %.1f m ahead while walking to $destination. The user now faces heading %d degrees from the scan start. Call set_plan with new legs from here."
                .format(ahead, guide.relativeToScanStart()),
            interrupt = false,
        )
    }

    override fun onCleared() {
        stt.stop()
        tts.stop()
        gemini.close()
        beeper.stop()
        motion.stop()
        urgentVoice.close()
        depthGuard?.close()
        objectGuard?.close()
        http.dispatcher.executorService.shutdown()
    }

    private var lastLogAt = 0L

    private val recentReadings = ArrayDeque<ObstacleReading>()

    private fun smooth(raw: ObstacleReading): ObstacleReading {
        recentReadings.addLast(raw)
        if (recentReadings.size > Config.SMOOTHING_FRAMES) recentReadings.removeFirst()
        fun median(pick: (ObstacleReading) -> Float) = recentReadings.map(pick).sorted()[recentReadings.size / 2]
        fun nearestObject(zone: Int) = recentReadings.minOf { it.objectZones[zone] }
        return raw.copy(
            left = minOf(median { it.left }, nearestObject(0)),
            center = minOf(median { it.center }, nearestObject(1)),
            right = minOf(median { it.right }, nearestObject(2)),
        )
    }

    private fun onReading(raw: ObstacleReading) {
        val reading = smooth(raw)
        latestReading = reading
        logReading(reading)
        _safety.update { it.copy(reading = reading, preview = reading.preview ?: it.preview) }

        val now = SystemClock.elapsedRealtime()
        val walking = motion.isWalking || motion.steppedRecently || isApproaching(reading, now)
        if (walking) lastWalkingAt = now
        val nearest = reading.nearest
        val beepLimit = if (walking) Config.BEEP_START_M else Config.BEEP_WHILE_STILL_M
        if (!reading.calibrated || nearest > beepLimit) {
            beeper.silence()
        } else {
            val pan = when (nearest) {
                reading.center -> 0f
                reading.left -> -0.8f
                else -> 0.8f
            }
            beeper.update(nearest, pan)
        }

        if (!reading.calibrated) return
        if (blockedSince != 0L) {
            clearFrames = if (reading.center > Config.STOP_DISTANCE_M + Config.CLEAR_MARGIN_M) clearFrames + 1 else 0
            if (clearFrames >= Config.CLEAR_FRAMES) {
                blockedSince = 0
                tts.stopSpeaking()
                val played = urgentVoice.play(UrgentVoice.Phrase.CLEAR)
                remember(UrgentVoice.Phrase.CLEAR.text)
                Log.i(TAG, "URGENT CLEAR played=$played ${reading.describe()}")
            } else if (now - blockedSince > Config.BLOCKED_TIMEOUT_MS) {
                blockedSince = 0
            }
        }
        if (!walking) return
        if (now - lastUrgentAt < Config.URGENT_COOLDOWN_MS) return
        if (guide.active) {
            if (guide.mode != RoomGuide.Mode.WALKING || reading.center >= Config.STOP_DISTANCE_M) return
            lastUrgentAt = now
            Log.i(TAG, "Obstacle during plan: ${reading.describe()}")
            guide.obstacleAhead()
            return
        }
        val freeSide = Config.STOP_DISTANCE_M + Config.CLEAR_MARGIN_M
        val phrase = when {
            reading.center < Config.STOP_DISTANCE_M && reading.left > freeSide && reading.left >= reading.right ->
                UrgentVoice.Phrase.STOP_LEFT
            reading.center < Config.STOP_DISTANCE_M && reading.right > freeSide -> UrgentVoice.Phrase.STOP_RIGHT
            reading.center < Config.STOP_DISTANCE_M -> UrgentVoice.Phrase.STOP_BLOCKED
            reading.left < Config.SIDE_WARNING_M -> UrgentVoice.Phrase.LEFT
            reading.right < Config.SIDE_WARNING_M -> UrgentVoice.Phrase.RIGHT
            else -> return
        }
        lastUrgentAt = now
        if (reading.center < Config.STOP_DISTANCE_M) {
            blockedSince = now
            clearFrames = 0
        }
        synchronized(this) {
            turnSilenced = true
            pendingSpeech.clear()
            lastTurnEndedAt = now + Config.URGENT_COOLDOWN_MS
        }
        tts.stopSpeaking()
        val played = urgentVoice.play(phrase)
        remember(phrase.text)
        Log.i(TAG, "URGENT ${phrase.name} played=$played ${reading.describe()}")
    }

    private fun logReading(reading: ObstacleReading) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAt < 250) return
        lastLogAt = now
        val guard = depthGuard
        Log.d(
            TAG,
            "depth L=%.2f C=%.2f R=%.2f ok=%s ms=%d | %s| tilt=%.0f head=%.0f steps=%d peakAcc=%.2f walking=%s".format(
                reading.left, reading.center, reading.right, reading.calibrated, reading.inferenceMs,
                guard?.lastFit,
                motion.pitchDownDeg, motion.headingDeg, motion.steps, motion.peakAccel, motion.isWalking,
            ),
        )
        motion.peakAccel = 0f
    }

    private fun isApproaching(reading: ObstacleReading, now: Long): Boolean {
        if (reading.calibrated && reading.center.isFinite()) centerHistory.addLast(now to reading.center)
        while (centerHistory.isNotEmpty() && now - centerHistory.first().first > Config.APPROACH_WINDOW_MS) centerHistory.removeFirst()
        val farthest = centerHistory.maxOfOrNull { it.second } ?: return false
        return reading.center.isFinite() && farthest - reading.center >= Config.APPROACH_DROP_M
    }

    private fun checkHolding() {
        val pitch = motion.pitchDownDeg
        val now = SystemClock.elapsedRealtime()
        if (!motion.hasOrientation || pitch in -20f..55f) {
            badTiltSince = 0
            return
        }
        if (badTiltSince == 0L) badTiltSince = now
        if (now - badTiltSince < Config.HOLD_UP_DELAY_MS || now - lastHoldUpAt < Config.HOLD_UP_COOLDOWN_MS) return
        lastHoldUpAt = now
        tts.stopSpeaking()
        remember(Config.HOLD_UP_MESSAGE)
        tts.speak(Config.HOLD_UP_MESSAGE)
    }

    private fun sensorLine(): String {
        val reading = latestReading
        val depth = reading?.describe() ?: "depth not ready"
        val blocked = reading != null && (blockedSince != 0L || reading.center < Config.STOP_DISTANCE_M + Config.CLEAR_MARGIN_M)
        val warning = if (blocked) " PATH AHEAD IS BLOCKED: the user cannot walk straight. Tell them which way to turn first." else ""
        val target = if (guide.hasScan) " Current heading: ${guide.relativeToScanStart()} degrees clockwise from the scan start." else ""
        return "Phone sensors: $depth; heading %.0f degrees.$warning$target".format(motion.headingDeg)
    }

    private fun ask(text: String, interrupt: Boolean, silent: Boolean = false) {
        synchronized(this) {
            if (interrupt) tts.stopSpeaking()
            turnSilenced = silent
            blockedNoticeGiven = false
            firstChunkPending = true
            pendingSpeech.clear()
            turnText.clear()
            if (!gemini.sendUserText("$text\n${sensorLine()}")) {
                setStatus("Gemini is not connected yet")
                return
            }
            modelBusy = true
            busySince = SystemClock.elapsedRealtime()
            askedAt = busySince
        }
    }

    private fun maybeRequestNavigationUpdate() {
        val now = SystemClock.elapsedRealtime()
        if (modelBusy && now - busySince > 20_000) modelBusy = false
        if (guide.active) return
        if (!navigationOn || !gemini.isReady || lastFrameAt == 0L || blockedSince != 0L) return
        if (modelBusy || tts.isSpeaking || now - lastTurnEndedAt < Config.NAVIGATION_GAP_MS) return
        if (now - lastWalkingAt > Config.NARRATE_AFTER_WALKING_MS) return
        if (now - lastUserSpokeAt < Config.QUIET_AFTER_USER_MS) return
        ask(Config.NAVIGATION_PROMPT, interrupt = false)
    }

    private fun speakCompleteSentences(final: Boolean) {
        val text = pendingSpeech.toString()
        val cut = when {
            final -> text.length
            lastSentenceEnd(text) > 0 -> lastSentenceEnd(text)
            text.length >= Config.EARLY_CLAUSE_CHARS -> text.lastIndexOf(", ").let { if (it >= Config.EARLY_CLAUSE_CHARS / 2) it + 1 else -1 }
            else -> -1
        }
        if (cut <= 0) return
        pendingSpeech.delete(0, cut)
        val sentence = text.substring(0, cut).replace(Config.NO_CHANGE, "").trim()
        if (!sentence.any { it.isLetterOrDigit() }) return
        val ahead = latestReading?.center ?: Float.POSITIVE_INFINITY
        val pathBlocked = blockedSince != 0L || ahead < Config.STOP_DISTANCE_M + Config.CLEAR_MARGIN_M
        if (pathBlocked && Config.GO_AHEAD_WORDS.containsMatchIn(sentence)) {
            Log.i(TAG, "Dropped unsafe advice while blocked: $sentence")
            if (!blockedNoticeGiven) {
                blockedNoticeGiven = true
                remember(Config.BLOCKED_REPLACEMENT)
                tts.speak(Config.BLOCKED_REPLACEMENT)
            }
            return
        }
        if (guide.active && Config.DIRECTION_WORDS.containsMatchIn(sentence)) {
            Log.i(TAG, "Dropped direction during guidance: $sentence")
            return
        }
        Log.i(TAG, "Iris says: $sentence")
        remember(sentence)
        tts.speak("$sentence ")
    }

    @Volatile
    private var lastSpeechHeardAt = 0L

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }

    private fun remember(spoken: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(recentlySpoken) {
            recentlySpoken.addLast(now to spoken)
            while (recentlySpoken.isNotEmpty() && now - recentlySpoken.first().first > Config.ECHO_WINDOW_MS) recentlySpoken.removeFirst()
        }
    }

    private fun isEcho(heard: String): Boolean {
        val heardWords = words(heard)
        if (heardWords.isEmpty()) return true
        val now = SystemClock.elapsedRealtime()
        if (!tts.isSpeaking && now - lastSpeechHeardAt > Config.ECHO_TAIL_MS) return false
        val spokenWords = synchronized(recentlySpoken) {
            recentlySpoken.filter { now - it.first < Config.ECHO_WINDOW_MS }.flatMap { words(it.second) }.toSet()
        }
        if (spokenWords.isEmpty()) return false
        return heardWords.count { it in spokenWords } >= heardWords.size * Config.ECHO_OVERLAP
    }

    private fun lastSentenceEnd(text: String): Int {
        if (text.length > 1 && text.last() in ".!?" && !text[text.length - 2].isDigit()) return text.length
        for (i in text.length - 2 downTo 0) {
            if (text[i] in ".!?" && (text[i + 1].isWhitespace() || text[i + 1].isUpperCase())) return i + 1
        }
        return -1
    }

    private fun setStatus(message: String) {
        _state.update { it.copy(status = message) }
    }
}
