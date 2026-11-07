package com.eldervision

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

data class ScanShot(val headingDeg: Int, val jpeg: ByteArray, val ahead: String)

data class Leg(val headingDeg: Float, val steps: Int, val note: String)

class RoomGuide(
    private val motion: MotionTracker,
    private val say: (String) -> Unit,
    private val onScanDone: (destination: String, shots: List<ScanShot>) -> Unit,
    private val onBlocked: (destination: String) -> Unit,
) {
    enum class Mode { IDLE, SCANNING, PLANNING, TURNING, WALKING, PAUSED, RECORDING }

    @Volatile
    var mode = Mode.IDLE
        private set

    var destination = ""
        private set

    private var scanStart = Float.NaN
    private var lastScanHeading = 0f
    private var turned = 0f
    private var nextShotDeg = 0f
    private var scanStartedAt = 0L
    private var lastProgressAt = 0L
    private val shots = ArrayList<ScanShot>()

    private var legs = emptyList<Leg>()
    private var legIndex = 0
    private var alignedTicks = 0
    private var walkStartSteps = 0
    private var walkingMs = 0L
    private var lastTickAt = 0L
    private var lastRemaining = -1
    private var lastInstruction = ""
    private var lastInstructionAt = 0L
    private var planningSince = 0L
    private var fromMemory = false
    private var recordStart = 0f
    private var recordSteps = 0
    private val recordedHeadings = ArrayList<Float>()

    val active: Boolean get() = mode != Mode.IDLE && mode != Mode.RECORDING

    val hasScan: Boolean get() = !scanStart.isNaN()

    fun relativeToScanStart(heading: Float = motion.headingDeg): Int = wrap360(heading - scanStart).toInt()

    fun startRoute(name: String, route: List<Leg>) {
        destination = name
        scanStart = motion.headingDeg
        fromMemory = true
        Log.i(TAG, "Route from memory: $name")
        setPlan(route)
    }

    fun startRecording(name: String) {
        destination = name
        recordStart = motion.headingDeg
        recordSteps = motion.steps
        recordedHeadings.clear()
        mode = Mode.RECORDING
        Log.i(TAG, "Recording route $name")
    }

    fun finishRecording(): List<Leg> {
        if (mode != Mode.RECORDING) return emptyList()
        mode = Mode.IDLE
        val legs = ArrayList<Leg>()
        var sumSin = 0.0
        var sumCos = 0.0
        var count = 0
        fun mean() = Math.toDegrees(kotlin.math.atan2(sumSin, sumCos)).toFloat()
        for (heading in recordedHeadings) {
            if (count > 0 && abs(wrap180(heading - mean())) > Config.RECORD_TURN_DEG) {
                legs += Leg(wrap360(mean()), count, "")
                sumSin = 0.0
                sumCos = 0.0
                count = 0
            }
            sumSin += kotlin.math.sin(Math.toRadians(heading.toDouble()))
            sumCos += kotlin.math.cos(Math.toRadians(heading.toDouble()))
            count++
        }
        if (count > 0) legs += Leg(wrap360(mean()), count, "")
        Log.i(TAG, "Recorded route $destination: " + legs.joinToString(" | ") { "%.0f deg %d steps".format(it.headingDeg, it.steps) })
        return legs
    }

    fun resumeIfPaused() {
        if (mode != Mode.PAUSED) return
        mode = Mode.WALKING
        say(Config.ROUTE_RESUME)
    }

    fun startScan(target: String) {
        fromMemory = false
        destination = target
        scanStart = motion.headingDeg
        lastScanHeading = scanStart
        turned = 0f
        nextShotDeg = 0f
        shots.clear()
        scanStartedAt = SystemClock.elapsedRealtime()
        lastProgressAt = scanStartedAt
        mode = Mode.SCANNING
        Log.i(TAG, "Scan started for $target at heading %.0f".format(scanStart))
        say(Config.SCAN_START)
    }

    fun onFrame(frame: Bitmap, reading: ObstacleReading?) {
        if (mode != Mode.SCANNING) return
        val now = SystemClock.elapsedRealtime()
        val heading = motion.headingDeg
        turned += wrap180(heading - lastScanHeading)
        lastScanHeading = heading
        if (abs(turned) >= nextShotDeg) {
            val label = relativeToScanStart(heading)
            val ahead = reading?.let { if (it.calibrated) "ahead " + metres(it.center) else "no depth" } ?: "no depth"
            shots += ScanShot(label, frame.toJpeg(Config.SCAN_MAX_EDGE, Config.JPEG_QUALITY), ahead)
            Log.i(TAG, "Scan shot ${shots.size} at $label deg, $ahead")
            nextShotDeg += Config.SCAN_STEP_DEG
            lastProgressAt = now
        }
        val complete = abs(turned) >= Config.SCAN_FULL_DEG
        val timedOut = now - scanStartedAt > Config.SCAN_TIMEOUT_MS
        if (complete || (timedOut && shots.size >= Config.SCAN_MIN_SHOTS)) {
            mode = Mode.PLANNING
            planningSince = SystemClock.elapsedRealtime()
            say(Config.SCAN_DONE)
            onScanDone(destination, shots.toList())
            return
        }
        if (timedOut) {
            cancel()
            say(Config.SCAN_FAILED)
            return
        }
        if (now - lastProgressAt > Config.SCAN_NUDGE_MS) {
            lastProgressAt = now
            say(Config.SCAN_NUDGE)
        }
    }

    fun setPlan(newLegs: List<Leg>) {
        if (newLegs.isEmpty()) return
        legs = newLegs
        legIndex = 0
        startLeg()
        Log.i(TAG, "Plan: " + legs.joinToString(" | ") { "%.0f deg %d steps %s".format(it.headingDeg, it.steps, it.note) })
    }

    fun cancel() {
        mode = Mode.IDLE
        legs = emptyList()
        Log.i(TAG, "Guidance cancelled")
    }

    fun obstacleAhead(): Boolean {
        if (mode != Mode.WALKING && mode != Mode.RECORDING) return true
        if (mode != Mode.WALKING) return false
        val remaining = legs[legIndex].steps - stepsDone()
        if (remaining <= Config.ARRIVAL_SLACK_STEPS) {
            if (legIndex == legs.lastIndex) {
                arrive()
            } else {
                legIndex++
                startLeg()
                say("Stop.")
            }
            return true
        }
        if (fromMemory) {
            mode = Mode.PAUSED
            say(Config.ROUTE_PAUSED)
            return true
        }
        mode = Mode.PLANNING
        planningSince = SystemClock.elapsedRealtime()
        say(Config.PLAN_BLOCKED)
        onBlocked(destination)
        return true
    }

    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTickAt == 0L) 0L else now - lastTickAt
        lastTickAt = now
        when (mode) {
            Mode.TURNING -> turnTick(now)
            Mode.WALKING -> walkTick(now, dt)
            Mode.RECORDING -> while (motion.steps > recordSteps) {
                recordSteps++
                recordedHeadings += wrap360(motion.headingDeg - recordStart)
            }
            Mode.PLANNING -> if (now - planningSince > Config.PLAN_TIMEOUT_MS) {
                cancel()
                say(Config.PLAN_FAILED)
            }
            else -> Unit
        }
    }

    private fun startLeg() {
        mode = Mode.TURNING
        alignedTicks = 0
        lastInstruction = ""
        lastInstructionAt = 0L
    }

    private fun targetRel(): Float = wrap180(scanStart + legs[legIndex].headingDeg - motion.headingDeg)

    private fun turnTick(now: Long) {
        val rel = targetRel()
        if (abs(rel) <= Config.ALIGNED_DEG) {
            if (++alignedTicks < Config.ALIGNED_TICKS) return
            val leg = legs[legIndex]
            mode = Mode.WALKING
            walkStartSteps = motion.steps
            walkingMs = 0L
            lastRemaining = leg.steps
            instruct("Stop turning. Now walk " + stepsWord(leg.steps) + " slowly.", now, force = true)
            return
        }
        alignedTicks = 0
        val instruction = when {
            rel > Config.BIG_TURN_DEG -> "Turn right."
            rel > 0 -> "A little right."
            rel < -Config.BIG_TURN_DEG -> "Turn left."
            else -> "A little left."
        }
        instruct(instruction, now)
    }

    private fun walkTick(now: Long, dt: Long) {
        if (motion.steppedRecently || motion.isWalking) walkingMs += dt
        val leg = legs[legIndex]
        val remaining = leg.steps - stepsDone()
        if (remaining <= 0) {
            if (legIndex == legs.lastIndex) {
                arrive()
                return
            }
            legIndex++
            startLeg()
            instruct("Stop.", now, force = true)
            return
        }
        if (remaining < lastRemaining && remaining <= 2) {
            lastRemaining = remaining
            instruct(if (remaining == 1) "One more step." else "Two more steps.", now, force = true)
            return
        }
        val rel = targetRel()
        if (abs(rel) > Config.DRIFT_DEG) {
            instruct(if (rel > 0) "Bear a little right." else "Bear a little left.", now)
        }
    }

    private fun stepsDone(): Int = maxOf(motion.steps - walkStartSteps, (walkingMs / Config.MS_PER_STEP).toInt())

    private fun arrive() {
        mode = Mode.IDLE
        legs = emptyList()
        say("Stop. You should be at the $destination now. Reach out slowly to feel for it.")
        Log.i(TAG, "Arrived at $destination")
    }

    private fun instruct(text: String, now: Long, force: Boolean = false) {
        if (!force) {
            if (now - lastInstructionAt < Config.GUIDE_MIN_GAP_MS) return
            if (text == lastInstruction && now - lastInstructionAt < Config.GUIDE_REPEAT_MS) return
        }
        lastInstruction = text
        lastInstructionAt = now
        Log.i(TAG, "Guide: $text")
        say(text)
    }

    private fun stepsWord(steps: Int) = if (steps == 1) "one step" else "$steps steps"

    private fun metres(value: Float) = if (value.isFinite()) "%.1f m".format(value) else "clear"

    companion object {
        private const val TAG = "Iris"

        fun wrap180(deg: Float): Float = ((deg % 360f) + 540f) % 360f - 180f

        fun wrap360(deg: Float): Float = ((deg % 360f) + 360f) % 360f
    }
}
