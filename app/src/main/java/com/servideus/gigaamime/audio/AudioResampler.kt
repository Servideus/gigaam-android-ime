package com.servideus.gigaamime.audio

import kotlin.math.floor
import kotlin.math.roundToInt

object AudioResampler {
    fun resampleLinear(input: ShortArray, sourceSampleRate: Int, targetSampleRate: Int): ShortArray {
        if (input.isEmpty() || sourceSampleRate <= 0 || targetSampleRate <= 0) {
            return ShortArray(0)
        }
        if (sourceSampleRate == targetSampleRate) {
            return input
        }

        val ratio = targetSampleRate.toDouble() / sourceSampleRate.toDouble()
        val outputSize = maxOf(1, (input.size * ratio).roundToInt())
        val output = ShortArray(outputSize)

        for (index in 0 until outputSize) {
            val sourcePosition = index / ratio
            val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceIn(0, input.lastIndex)
            val fraction = sourcePosition - left.toDouble()
            val sample = input[left] * (1.0 - fraction) + input[right] * fraction
            output[index] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }
}
