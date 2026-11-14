package com.servideus.gigaamime.data

/** Thread and precision settings for each profile live in the native core; only the id crosses JNI. */
enum class SpeedProfile(val id: String) {
    BALANCED("balanced"),
    FAST("fast"),
    QUALITY("quality");

    companion object {
        fun fromId(value: String?): SpeedProfile = entries.firstOrNull { it.id == value } ?: BALANCED
    }
}

enum class AcceleratorMode(val id: String) {
    AUTO("auto"),
    CPU("cpu");

    companion object {
        fun fromId(value: String?): AcceleratorMode = entries.firstOrNull { it.id == value } ?: AUTO
    }
}

data class RuntimeSettings(
    val speedProfile: SpeedProfile,
    val acceleratorMode: AcceleratorMode,
    val warmupEnabled: Boolean,
)
