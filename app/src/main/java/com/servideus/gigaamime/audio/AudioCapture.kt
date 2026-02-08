package com.servideus.gigaamime.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.Collections
import kotlin.math.max

class AudioCapture(
    private val onChunkCaptured: ((chunk: ShortArray) -> Unit)? = null,
) {
    private val chunks = Collections.synchronizedList(mutableListOf<ShortArray>())
    @Volatile
    private var running = false
    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null

    var sampleRate: Int = 16_000
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) {
            return
        }
        val (record, selectedSampleRate, frameSize) = createAudioRecord()
        audioRecord = record
        sampleRate = selectedSampleRate
        chunks.clear()
        running = true

        record.startRecording()
        readThread = Thread {
            val buffer = ShortArray(frameSize)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    chunks.add(chunk)
                    onChunkCaptured?.invoke(chunk)
                }
            }
        }.apply {
            name = "gigaam-ime-audio-capture"
            start()
        }
    }

    fun stop(): ShortArray {
        if (!running) {
            return ShortArray(0)
        }
        running = false
        readThread?.join(1500)
        readThread = null

        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null

        val localChunks = chunks.toList()
        chunks.clear()
        val totalSamples = localChunks.sumOf { it.size }
        val merged = ShortArray(totalSamples)
        var offset = 0
        for (chunk in localChunks) {
            chunk.copyInto(merged, destinationOffset = offset)
            offset += chunk.size
        }
        return merged
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): Triple<AudioRecord, Int, Int> {
        for (candidateRate in SAMPLE_RATE_CANDIDATES) {
            val minBuffer = AudioRecord.getMinBufferSize(
                candidateRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                continue
            }
            val bufferSize = max(minBuffer * 2, candidateRate / 5)
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                candidateRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return Triple(candidate, candidateRate, max(1024, bufferSize / 2))
            }
            candidate.release()
        }
        throw IllegalStateException("Unable to initialize AudioRecord for supported sample rates")
    }

    private companion object {
        val SAMPLE_RATE_CANDIDATES = intArrayOf(16_000, 48_000, 44_100)
    }
}
