package com.servideus.gigaamime.data

enum class SpeedProfile(
    val id: String,
    val defaultSilenceHangoverMs: Int,
    val defaultMaxUtteranceMs: Int,
    val cpuIntraThreads: Int,
    val cpuInterThreads: Int,
    val cpuParallelExecution: Boolean,
    val nnapiUseFp16: Boolean,
) {
    BALANCED(
        id = "balanced",
        defaultSilenceHangoverMs = 700,
        defaultMaxUtteranceMs = 15_000,
        cpuIntraThreads = 4,
        cpuInterThreads = 1,
        cpuParallelExecution = false,
        nnapiUseFp16 = true,
    ),
    FAST(
        id = "fast",
        defaultSilenceHangoverMs = 450,
        defaultMaxUtteranceMs = 10_000,
        cpuIntraThreads = 6,
        cpuInterThreads = 1,
        cpuParallelExecution = false,
        nnapiUseFp16 = true,
    ),
    QUALITY(
        id = "quality",
        defaultSilenceHangoverMs = 900,
        defaultMaxUtteranceMs = 25_000,
        cpuIntraThreads = 4,
        cpuInterThreads = 1,
        cpuParallelExecution = true,
        nnapiUseFp16 = false,
    );

    companion object {
        fun fromId(value: String?): SpeedProfile {
            return entries.firstOrNull { it.id == value } ?: BALANCED
        }
    }
}

enum class AcceleratorMode(val id: String) {
    AUTO("auto"),
    CPU("cpu");

    companion object {
        fun fromId(value: String?): AcceleratorMode {
            return entries.firstOrNull { it.id == value } ?: AUTO
        }
    }
}

data class RuntimeSettings(
    val speedProfile: SpeedProfile,
    val autoStopEnabled: Boolean,
    val silenceHangoverMs: Int,
    val maxUtteranceMs: Int,
    val acceleratorMode: AcceleratorMode,
    val warmupEnabled: Boolean,
)
