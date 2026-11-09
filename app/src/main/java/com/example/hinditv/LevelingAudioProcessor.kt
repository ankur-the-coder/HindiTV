package com.example.hinditv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Automatic loudness leveling on the decoded audio (16-bit PCM).
 * It measures how loud the current channel is and adjusts its gain towards a common target,
 * so quiet channels are lifted and loud ones are lowered. It never touches the system volume:
 * the TV volume keys still control the final loudness.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LevelingAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val TARGET_RMS = 2600.0
        private const val GATE_MS = 1600.0          // below ~-58 dBFS counts as silence
        private const val MIN_GAIN = 0.25
        private const val MAX_GAIN = 6.0
        private const val ATTACK = 0.5              // seconds, level rising
        private const val RELEASE = 3.0             // seconds, level falling
        private const val PEAK_LIMIT = 32000.0
        private const val WARMUP_SECONDS = 2.0
    }

    private var sampleRate = 48000
    private var channels = 2
    private var levelMs = -1.0
    private var gain = 1.0
    private var warmup = WARMUP_SECONDS

    @Volatile
    private var resetRequested = false

    /** Call when switching channel so the new channel is measured quickly. */
    fun resetLevel() {
        resetRequested = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = max(1, inputAudioFormat.sampleRate)
        channels = max(1, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        levelMs = -1.0
        gain = 1.0
        warmup = WARMUP_SECONDS
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        if (size < 2) {
            inputBuffer.position(limit)
            return
        }
        if (resetRequested) {
            resetRequested = false
            onFlush()
        }

        val count = size / 2

        // pass 1: loudness and peak
        var sumSq = 0.0
        var peak = 0
        var i = pos
        for (n in 0 until count) {
            val s = (inputBuffer.get(i + 1).toInt() shl 8) or (inputBuffer.get(i).toInt() and 0xFF)
            sumSq += s.toDouble() * s
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }

        val frames = max(1, count / channels)
        val dt = frames.toDouble() / sampleRate
        val ms = sumSq / count

        if (ms > GATE_MS) {
            if (levelMs < 0) {
                levelMs = ms
            } else {
                val tau = if (warmup > 0) 0.12 else if (ms > levelMs) ATTACK else RELEASE
                levelMs += (1.0 - exp(-dt / tau)) * (ms - levelMs)
            }
        }
        warmup = max(0.0, warmup - dt)

        var target = if (levelMs > 0) TARGET_RMS / sqrt(levelMs) else 1.0
        if (target < MIN_GAIN) target = MIN_GAIN
        if (target > MAX_GAIN) target = MAX_GAIN

        val gTau = if (target < gain) 0.15 else if (warmup > 0) 0.2 else 0.8
        var newGain = gain + (target - gain) * (1.0 - exp(-dt / gTau))

        var startGain = gain
        if (peak > 0) {
            val maxGain = PEAK_LIMIT / peak
            if (newGain > maxGain) newGain = maxGain
            if (startGain > maxGain) startGain = maxGain
        }

        // pass 2: apply gain with a smooth ramp
        val out = replaceOutputBuffer(size)
        val step = (newGain - startGain) / count
        var g = startGain
        i = pos
        for (n in 0 until count) {
            g += step
            val s = (inputBuffer.get(i + 1).toInt() shl 8) or (inputBuffer.get(i).toInt() and 0xFF)
            var v = (s * g).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            out.put(v.toByte())
            out.put((v shr 8).toByte())
            i += 2
        }
        if (size % 2 == 1) out.put(inputBuffer.get(limit - 1))

        gain = newGain
        inputBuffer.position(limit)
        out.flip()
    }
}
