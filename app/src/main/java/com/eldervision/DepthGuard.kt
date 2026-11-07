package com.eldervision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class ObstacleReading(
    val left: Float,
    val center: Float,
    val right: Float,
    val calibrated: Boolean,
    val inferenceMs: Long,
    val preview: Bitmap?,
    val objects: String = "",
    val objectZones: FloatArray = FloatArray(3) { Float.POSITIVE_INFINITY },
) {
    val nearest: Float get() = minOf(left, center, right)

    fun describe(): String {
        if (!calibrated) return "depth unavailable (phone tilted too far)"
        fun zone(name: String, metres: Float) =
            if (metres.isInfinite()) "$name clear" else "$name %.1f m".format(metres)
        val zones = listOf(zone("left", left), zone("ahead", center), zone("right", right)).joinToString(", ")
        return if (objects.isEmpty()) zones else "$zones; detected $objects"
    }
}

class DepthGuard(context: Context) {
    private val size = 256
    private val input = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
    private val depth = FloatArray(size * size)
    private val pixels = IntArray(size * size)
    private val interpreter: Interpreter
    private var gpu: GpuDelegate? = null
    private var frameCount = 0
    private var closed = false

    var lastFit = "none"
        private set

    val backend: String

    var verticalFovDeg = Config.DEFAULT_VERTICAL_FOV_DEG

    init {
        val model = context.assets.openFd(Config.DEPTH_MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        var delegate: GpuDelegate? = null
        val gpuInterpreter = runCatching {
            delegate = GpuDelegate()
            Interpreter(model, Interpreter.Options().addDelegate(delegate))
        }.getOrNull()
        if (gpuInterpreter != null) {
            gpu = delegate
            interpreter = gpuInterpreter
            backend = "GPU"
        } else {
            delegate?.close()
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4).setUseXNNPACK(false))
            backend = "CPU"
        }
    }

    @Synchronized
    fun analyze(frame: Bitmap, pitchDownDeg: Float, hasOrientation: Boolean, objects: List<SeenObject>): ObstacleReading? {
        if (closed) return null
        val started = SystemClock.elapsedRealtime()
        runModel(frame)
        val obstacle = BooleanArray(size * size)
        val usable = hasOrientation && pitchDownDeg in Config.MIN_TILT_DEG..Config.MAX_TILT_DEG
        val status = StringBuilder()
        val zones = FloatArray(3) { zone -> if (usable) measureZone(zone, pitchDownDeg, obstacle, status) else INF }
        val named = ArrayList<String>()
        val objectZones = FloatArray(3) { Float.POSITIVE_INFINITY }
        if (usable) {
            for (seen in objects) {
                val row = (seen.bottom * size).toInt().coerceIn(0, size - 1)
                val metres = floorDepth(row, pitchDownDeg)
                if (metres > Config.MAX_OBSTACLE_M) continue
                val zoneEdges = floatArrayOf(0f, 0.37f, 0.63f, 1f)
                val sides = ArrayList<String>()
                for (zone in 0 until 3) {
                    if (seen.right <= zoneEdges[zone] || seen.left >= zoneEdges[zone + 1]) continue
                    objectZones[zone] = minOf(objectZones[zone], metres)
                    sides += listOf("left", "ahead", "right")[zone]
                }
                named += "${seen.name} ${sides.joinToString("/")} %.1f m".format(metres)
            }
        }
        lastFit = if (usable) status.toString() + named.joinToString(", ") else "tilt out of range"
        val preview = if (frameCount++ % 3 == 0) previewBitmap(obstacle) else null
        return ObstacleReading(
            zones[0], zones[1], zones[2], usable, SystemClock.elapsedRealtime() - started, preview, named.joinToString(", "), objectZones,
        )
    }

    @Synchronized
    fun close() {
        closed = true
        interpreter.close()
        gpu?.close()
    }

    private fun runModel(frame: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(frame, size, size, true)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== frame) scaled.recycle()
        input.rewind()
        for (pixel in pixels) {
            input.putFloat((((pixel shr 16) and 0xFF) / 255f - 0.485f) / 0.229f)
            input.putFloat((((pixel shr 8) and 0xFF) / 255f - 0.456f) / 0.224f)
            input.putFloat(((pixel and 0xFF) / 255f - 0.406f) / 0.225f)
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(depth)
    }

    private fun angleBelowAxisRad(row: Int): Double {
        val v = (row + 0.5) / size
        return atan((v - 0.5) * 2.0 * tan(Math.toRadians(verticalFovDeg / 2.0)))
    }

    private fun floorDepth(row: Int, pitchDownDeg: Float): Float {
        val alpha = angleBelowAxisRad(row)
        val theta = Math.toRadians(pitchDownDeg.toDouble()) + alpha
        if (theta < Math.toRadians(3.0)) return INF
        return (Config.CAMERA_HEIGHT_M / sin(theta) * cos(alpha)).toFloat()
    }

    private fun fitFloor(x0: Int, x1: Int, pitchDownDeg: Float): Pair<Float, Float>? {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val rowValues = FloatArray(x1 - x0)
        for (row in (size * Config.FLOOR_BAND_TOP).toInt() until (size * 0.98).toInt()) {
            val floor = floorDepth(row, pitchDownDeg)
            if (floor > 6f) continue
            for (x in x0 until x1) rowValues[x - x0] = depth[row * size + x]
            rowValues.sort()
            xs += 1f / floor
            ys += rowValues[rowValues.size / 2]
        }
        if (xs.size < Config.CALIBRATION_MIN_ROWS) return null
        val meanX = xs.average().toFloat()
        val meanY = ys.average().toFloat()
        var sxy = 0f
        var sxx = 0f
        var syy = 0f
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0f || syy <= 0f) return null
        val a = sxy / sxx
        if (a <= 0f || sxy * sxy / (sxx * syy) < Config.CALIBRATION_MIN_R2) return null
        return a to meanY - a * meanX
    }

    private fun measureZone(zone: Int, pitchDownDeg: Float, obstacle: BooleanArray, status: StringBuilder): Float {
        val bounds = floatArrayOf(0.10f, 0.37f, 0.63f, 0.90f)
        val x0 = (bounds[zone] * size).toInt()
        val x1 = (bounds[zone + 1] * size).toInt()
        val label = "LCR"[zone]
        val fit = fitFloor(x0, x1, pitchDownDeg)
        if (fit == null) {
            val bottom = floorDepth(size - 1, pitchDownDeg)
            status.append("$label:blocked ")
            if (bottom > Config.BLOCKED_MAX_M) return INF
            for (row in (size * Config.FLOOR_BAND_TOP).toInt() until size) for (x in x0 until x1) obstacle[row * size + x] = true
            return bottom
        }
        val (scale, offset) = fit
        status.append("$label:floor ")
        val firstRow = (size * 0.08).toInt()
        val distances = ArrayList<Float>()
        for (row in firstRow until size) {
            val floor = floorDepth(row, pitchDownDeg)
            for (x in x0 until x1) {
                val index = row * size + x
                val inverse = depth[index] - offset
                if (inverse <= 0f) continue
                val metres = scale / inverse
                if (metres < Config.MAX_OBSTACLE_M && metres < floor * Config.FLOOR_MARGIN) {
                    obstacle[index] = true
                    distances += metres
                }
            }
        }
        val needed = maxOf(20, ((x1 - x0) * (size - firstRow) * Config.OBSTACLE_AREA_FRACTION).toInt())
        return if (distances.size < needed) INF else distances.sorted()[needed - 1]
    }

    private fun previewBitmap(obstacle: BooleanArray): Bitmap {
        val step = 2
        val side = size / step
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (value in depth) {
            if (value < min) min = value
            if (value > max) max = value
        }
        val range = if (abs(max - min) < 1e-6f) 1f else max - min
        val colors = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val index = (y * step) * size + x * step
            val gray = (((depth[index] - min) / range) * 255).toInt().coerceIn(0, 255)
            colors[y * side + x] = if (obstacle[index]) {
                (0xFF shl 24) or (255 shl 16) or ((gray / 3) shl 8) or (gray / 3)
            } else {
                (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return Bitmap.createBitmap(colors, side, side, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val INF = Float.POSITIVE_INFINITY
    }
}
