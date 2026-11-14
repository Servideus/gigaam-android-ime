package com.servideus.gigaamime.data

import android.content.Context

class ModelSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAppendTrailingSpace(): Boolean = preferences.getBoolean(KEY_APPEND_TRAILING_SPACE, true)

    fun setAppendTrailingSpace(enabled: Boolean) = putBoolean(KEY_APPEND_TRAILING_SPACE, enabled)

    fun setSpeedProfile(profile: SpeedProfile) = putString(KEY_SPEED_PROFILE, profile.id)

    fun setAcceleratorMode(mode: AcceleratorMode) = putString(KEY_ACCELERATOR_MODE, mode.id)

    fun setWarmupEnabled(enabled: Boolean) = putBoolean(KEY_WARMUP_ENABLED, enabled)

    fun getRuntimeSettings(): RuntimeSettings = RuntimeSettings(
        speedProfile = SpeedProfile.fromId(preferences.getString(KEY_SPEED_PROFILE, null)),
        acceleratorMode = AcceleratorMode.fromId(preferences.getString(KEY_ACCELERATOR_MODE, null)),
        warmupEnabled = preferences.getBoolean(KEY_WARMUP_ENABLED, true),
    )

    private fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()

    private fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()

    private companion object {
        const val PREFS_NAME = "gigaam_ime_settings"
        const val KEY_APPEND_TRAILING_SPACE = "append_trailing_space"
        const val KEY_SPEED_PROFILE = "speed_profile"
        const val KEY_ACCELERATOR_MODE = "accelerator_mode"
        const val KEY_WARMUP_ENABLED = "warmup_enabled"
    }
}
