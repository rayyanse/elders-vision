package com.eldervision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

class MotionTracker(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation = FloatArray(9)

    @Volatile
    var headingDeg = 0f
        private set

    @Volatile
    var pitchDownDeg = 0f
        private set

    @Volatile
    var hasOrientation = false
        private set

    @Volatile
    var steps = 0
        private set

    @Volatile
    private var lastStepAt = 0L

    @Volatile
    private var previousStepAt = 0L

    private var peakArmed = true

    @Volatile
    var peakAccel = 0f

    val steppedRecently: Boolean
        get() = SystemClock.elapsedRealtime() - lastStepAt < Config.RECENT_STEP_MS

    val isWalking: Boolean
        get() = SystemClock.elapsedRealtime() - previousStepAt < Config.WALKING_WINDOW_MS

    fun start() {
        val orientation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        orientation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val forwardX = -rotation[2]
                val forwardY = -rotation[5]
                val forwardZ = -rotation[8]
                pitchDownDeg = Math.toDegrees(-asin(forwardZ.coerceIn(-1f, 1f)).toDouble()).toFloat()
                headingDeg = ((Math.toDegrees(atan2(forwardX, forwardY).toDouble()) + 360.0) % 360.0).toFloat()
                hasOrientation = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val (x, y, z) = event.values
                val magnitude = sqrt(x * x + y * y + z * z)
                if (magnitude > peakAccel) peakAccel = magnitude
                val now = SystemClock.elapsedRealtime()
                if (peakArmed && magnitude > Config.STEP_PEAK_MS2 && now - lastStepAt > Config.STEP_MIN_GAP_MS) {
                    steps++
                    previousStepAt = lastStepAt
                    lastStepAt = now
                    peakArmed = false
                } else if (magnitude < Config.STEP_RESET_MS2) {
                    peakArmed = true
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
