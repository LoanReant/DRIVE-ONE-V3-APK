package com.driveone.app

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pure PCM oscillator: no microphone, no OBD, no pre-recorded external audio. */
internal class EngineSynth(private val sampleRate: Int = 22050) {
    private var phase = 0.0
    private var currentHz = 78.0
    private var currentGain = 0.0

    fun render(buffer: ShortArray, speedKmh: Float, acceleration: Float, loudness: Float = 1f) {
        val speed = speedKmh.coerceIn(0f, 240f)
        val accel = acceleration.coerceIn(-5f, 5f)
        // The pitch is an approximation, NOT the car's real RPM.
        val desiredHz = 76.0 + (speed * 0.83) + (max(0f, accel) * 22.0)
        val desiredGain = (0.19 + min(0.11, speed.toDouble() / 1000.0) +
            max(0f, accel) * 0.021).coerceIn(0.0, 0.33) * loudness.coerceIn(0f, 1f)
        for (i in buffer.indices) {
            currentHz += (desiredHz - currentHz) * 0.0006
            currentGain += (desiredGain - currentGain) * 0.00075
            phase += 2 * PI * currentHz / sampleRate
            if (phase >= 2 * PI) phase -= 2 * PI
            val p = phase
            // Slight asymmetry and several harmonics simulate engine character.
            val wave = (sin(p) * 0.48 + sin(2 * p + 0.18) * 0.29 +
                sin(3 * p + 0.42) * 0.15 + sin(0.5 * p) * 0.08)
            buffer[i] = (wave * currentGain * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }
} 
 