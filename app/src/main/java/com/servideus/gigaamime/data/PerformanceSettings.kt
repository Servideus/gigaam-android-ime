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
        /** CPU by default: on a Helio G99 phone AUTO (XNNPACK + NNAPI) was about twice as slow. */
        fun fromId(value: String?): AcceleratorMode = entries.firstOrNull { it.id == value } ?: CPU
    }
}

data class RuntimeSettings(
    val speedProfile: SpeedProfile,
    val acceleratorMode: AcceleratorMode,
    val warmupEnabled: Boolean,
)
