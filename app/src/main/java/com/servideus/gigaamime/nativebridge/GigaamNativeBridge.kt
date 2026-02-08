package com.servideus.gigaamime.nativebridge

object GigaamNativeBridge {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("gigaam_core")
    }.exceptionOrNull()

    fun isAvailable(): Boolean = loadError == null

    fun loadErrorSummary(): String {
        return loadError?.message ?: "ok"
    }

    fun isModelValid(modelsRootDir: String, modelId: String): Boolean {
        ensureAvailable()
        return nativeIsModelValid(modelsRootDir, modelId)
    }

    fun transcribe(modelsRootDir: String, modelId: String, pcm16: ShortArray, sampleRate: Int): String {
        ensureAvailable()
        return nativeTranscribe(modelsRootDir, modelId, pcm16, sampleRate)
    }

    fun warmup(modelsRootDir: String, modelId: String): String {
        ensureAvailable()
        return nativeWarmup(modelsRootDir, modelId)
    }

    fun setRuntimeOptions(modelId: String, speedProfile: String, acceleratorMode: String): String {
        ensureAvailable()
        return nativeSetRuntimeOptions(modelId, speedProfile, acceleratorMode)
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
        if (!isAvailable()) {
            throw IllegalStateException("Native core unavailable: ${loadErrorSummary()}")
        }
    }

    private external fun nativeIsModelValid(modelsRootDir: String, modelId: String): Boolean
    private external fun nativeTranscribe(
        modelsRootDir: String,
        modelId: String,
        pcm16: ShortArray,
        sampleRate: Int,
    ): String
    private external fun nativeWarmup(modelsRootDir: String, modelId: String): String
    private external fun nativeSetRuntimeOptions(
        modelId: String,
        speedProfile: String,
        acceleratorMode: String,
    ): String
    private external fun nativeGetLastProfilingSummary(): String

    private external fun nativeUnload()
}
