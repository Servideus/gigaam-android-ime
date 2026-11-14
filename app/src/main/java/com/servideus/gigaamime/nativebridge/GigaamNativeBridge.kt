package com.servideus.gigaamime.nativebridge

import com.servideus.gigaamime.data.RuntimeSettings

/**
 * JNI entry points of `libgigaam_core.so`. All calls block until the native engine lock is free,
 * so call them off the main thread.
 */
object GigaamNativeBridge {
    /** Prefix of the error strings that [transcribe] returns instead of text. */
    const val TRANSCRIBE_ERROR_PREFIX = "GigaAM error:"

    private val loadError: Throwable? = runCatching {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("gigaam_core")
    }.exceptionOrNull()

    fun isAvailable(): Boolean = loadError == null

    fun loadErrorSummary(): String = loadError?.message ?: "ok"

    /** Resamples to 16 kHz natively when [sampleRate] differs. */
    fun transcribe(modelsRootDir: String, modelId: String, pcm16: ShortArray, sampleRate: Int): String {
        ensureAvailable()
        return nativeTranscribe(modelsRootDir, modelId, pcm16, sampleRate)
    }

    /**
     * Number of samples at the start of a still-running recording that can already be transcribed
     * on their own; cut exactly where the whole recording would be cut. 0 while none is ready.
     */
    fun findSettledPiece(pcm16: ShortArray, sampleRate: Int): Int {
        ensureAvailable()
        return nativeFindSettledPiece(pcm16, sampleRate)
    }

    /** Loads the model if needed; runs one dummy pass only right after a fresh load. */
    fun warmup(modelsRootDir: String, modelId: String): String {
        ensureAvailable()
        return nativeWarmup(modelsRootDir, modelId)
    }

    /** Changed options drop the loaded session; the next call rebuilds it. */
    fun setRuntimeOptions(settings: RuntimeSettings): String {
        ensureAvailable()
        return nativeSetRuntimeOptions(settings.speedProfile.id, settings.acceleratorMode.id)
    }

    fun getLastProfilingSummary(): String {
        ensureAvailable()
        return nativeGetLastProfilingSummary()
    }

    fun unload() {
        if (isAvailable()) {
            nativeUnload()
        }
    }

    private fun ensureAvailable() {
        check(isAvailable()) { "Native core unavailable: ${loadErrorSummary()}" }
    }

    private external fun nativeTranscribe(
        modelsRootDir: String,
        modelId: String,
        pcm16: ShortArray,
        sampleRate: Int,
    ): String
    private external fun nativeFindSettledPiece(pcm16: ShortArray, sampleRate: Int): Int
    private external fun nativeWarmup(modelsRootDir: String, modelId: String): String
    private external fun nativeSetRuntimeOptions(speedProfile: String, acceleratorMode: String): String
    private external fun nativeGetLastProfilingSummary(): String
    private external fun nativeUnload()
}
