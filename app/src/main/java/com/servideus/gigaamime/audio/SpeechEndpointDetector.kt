package com.servideus.gigaamime.audio

import kotlin.math.max
import kotlin.math.sqrt

class SpeechEndpointDetector(
    sampleRate: Int,
    private val minSpeechMs: Int = 250,
    private val silenceHangoverMs: Int,
    private val maxUtteranceMs: Int,
    private val frameMs: Int = 20,
) {
    private val frameSamples = ((sampleRate * frameMs) / 1000).coerceAtLeast(1)
    private val frameBuffer = ShortArray(frameSamples)
    private var frameBufferFill = 0

    private var noiseFloorRms = INITIAL_NOISE_FLOOR_RMS
    private var totalMs = 0
    private var speechMs = 0
    private var trailingSilenceMs = 0
    private var speechDetected = false

    data class DetectionState(
        val shouldStop: Boolean,
        val totalMs: Int,
        val speechDetected: Boolean,
        val trailingSilenceMs: Int,
    )

    fun processChunk(chunk: ShortArray): DetectionState {
        var shouldStop = false
        for (sample in chunk) {
            frameBuffer[frameBufferFill] = sample
            frameBufferFill += 1
            if (frameBufferFill >= frameSamples) {
                frameBufferFill = 0
                if (processFrame()) {
                    shouldStop = true
                    break
                }
            }
        }
        return DetectionState(
            shouldStop = shouldStop,
            totalMs = totalMs,
            speechDetected = speechDetected,
            trailingSilenceMs = trailingSilenceMs,
        )
    }

    private fun processFrame(): Boolean {
        totalMs += frameMs
        if (totalMs >= maxUtteranceMs) {
            return true
        }

        val rms = frameRms(frameBuffer)
        val dynamicThreshold = max(MIN_SPEECH_RMS, noiseFloorRms * NOISE_MULTIPLIER)
        val isSpeechFrame = rms >= dynamicThreshold

        if (isSpeechFrame) {
            speechMs += frameMs
            speechDetected = speechMs >= minSpeechMs
            trailingSilenceMs = 0
        } else {
            noiseFloorRms = noiseFloorRms * NOISE_DECAY + rms * (1.0 - NOISE_DECAY)
            if (speechDetected) {
                trailingSilenceMs += frameMs
                if (trailingSilenceMs >= silenceHangoverMs) {
                    return true
                }
            }
        }

        return false
    }

    private fun frameRms(frame: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in frame) {
            val normalized = sample.toDouble() / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / frame.size.coerceAtLeast(1))
    }

    private companion object {
        const val MIN_SPEECH_RMS = 0.012
        const val INITIAL_NOISE_FLOOR_RMS = 0.008
        const val NOISE_MULTIPLIER = 2.2
        const val NOISE_DECAY = 0.95
    }
}
