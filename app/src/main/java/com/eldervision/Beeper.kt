package com.eldervision

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class Beeper {
    private val sampleRate = 44100
    private val chunkFrames = sampleRate / 50

    @Volatile
    private var intervalMs = 0L

    @Volatile
    private var frequency = 800.0

    @Volatile
    private var pan = 0f

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "beeper", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
    }

    fun update(distanceM: Float, panning: Float) {
        val closeness = ((Config.BEEP_START_M - distanceM) / (Config.BEEP_START_M - 0.4f)).coerceIn(0f, 1f)
        intervalMs = (600 - 500 * closeness).toLong()
        frequency = 600.0 + 500.0 * closeness
        pan = panning.coerceIn(-1f, 1f)
    }

    fun silence() {
        intervalMs = 0
    }

    private fun loop() {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, chunkFrames * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val silence = ShortArray(chunkFrames * 2)
        var sinceBeepMs = Long.MAX_VALUE / 2
        while (running) {
            val interval = intervalMs
            if (interval > 0 && sinceBeepMs >= interval) {
                val beep = beep(frequency, pan)
                track.write(beep, 0, beep.size)
                sinceBeepMs = Config.BEEP_LENGTH_MS
            } else {
                track.write(silence, 0, silence.size)
                sinceBeepMs += 20
            }
        }
        track.stop()
        track.release()
    }

    private fun beep(frequency: Double, pan: Float): ShortArray {
        val frames = (sampleRate * Config.BEEP_LENGTH_MS / 1000).toInt()
        val angle = (pan + 1f) / 2f * (PI / 2)
        val leftGain = cos(angle)
        val rightGain = sin(angle)
        val samples = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            val position = (i - frames / 2.0) / (frames / 4.0)
            val envelope = exp(-0.5 * position * position)
            val value = (sin(2 * PI * frequency * t) + 0.2 * sin(4 * PI * frequency * t)) * envelope * 0.5
            samples[i * 2] = (value * leftGain * Short.MAX_VALUE).toInt().toShort()
            samples[i * 2 + 1] = (value * rightGain * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }
}
