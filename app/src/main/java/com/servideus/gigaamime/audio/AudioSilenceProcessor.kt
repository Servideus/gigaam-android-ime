package com.servideus.gigaamime.audio

import kotlin.math.sqrt

object AudioSilenceProcessor {
    fun clipToMaxDuration(pcm16: ShortArray, sampleRate: Int, maxDurationMs: Int): ShortArray {
        if (pcm16.isEmpty() || sampleRate <= 0 || maxDurationMs <= 0) {
            return pcm16
        }
        val maxSamples = ((sampleRate.toLong() * maxDurationMs) / 1000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return if (pcm16.size <= maxSamples) pcm16 else pcm16.copyOf(maxSamples)
    }

    fun trimLeadingAndTrailingSilence(
        pcm16: ShortArray,
        sampleRate: Int,
        frameMs: Int = 20,
        minSpeechRms: Double = 0.012,
        paddingFrames: Int = 2,
    ): ShortArray {
        if (pcm16.isEmpty() || sampleRate <= 0) {
            return pcm16
        }
        val frameSamples = ((sampleRate * frameMs) / 1000).coerceAtLeast(1)
        val frameCount = (pcm16.size + frameSamples - 1) / frameSamples
        var firstSpeechFrame = -1
        var lastSpeechFrame = -1

        for (frameIdx in 0 until frameCount) {
            val frameStart = frameIdx * frameSamples
            val frameEnd = (frameStart + frameSamples).coerceAtMost(pcm16.size)
            val rms = frameRms(pcm16, frameStart, frameEnd)
            if (rms >= minSpeechRms) {
                if (firstSpeechFrame < 0) {
                    firstSpeechFrame = frameIdx
                }
                lastSpeechFrame = frameIdx
            }
        }

        if (firstSpeechFrame < 0 || lastSpeechFrame < 0) {
            return pcm16
        }

        val paddedStartFrame = (firstSpeechFrame - paddingFrames).coerceAtLeast(0)
        val paddedEndFrame = (lastSpeechFrame + paddingFrames).coerceAtMost(frameCount - 1)
        val startSample = paddedStartFrame * frameSamples
        val endSampleExclusive = ((paddedEndFrame + 1) * frameSamples).coerceAtMost(pcm16.size)
        if (startSample >= endSampleExclusive) {
            return pcm16
        }
        return pcm16.copyOfRange(startSample, endSampleExclusive)
    }

    private fun frameRms(samples: ShortArray, start: Int, endExclusive: Int): Double {
        if (endExclusive <= start) {
            return 0.0
        }
        var sumSquares = 0.0
        for (index in start until endExclusive) {
            val normalized = samples[index].toDouble() / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / (endExclusive - start))
    }
}
