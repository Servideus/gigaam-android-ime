package com.servideus.gigaamime.data

import android.content.Context

class ModelSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveModel(): GigaamModel {
        return GigaamModel.fromId(preferences.getString(KEY_ACTIVE_MODEL, GigaamModel.INT8.id))
    }

    fun setActiveModel(model: GigaamModel) {
        preferences.edit().putString(KEY_ACTIVE_MODEL, model.id).apply()
    }

    fun getAppendTrailingSpace(): Boolean {
        return preferences.getBoolean(KEY_APPEND_TRAILING_SPACE, true)
    }

    fun setAppendTrailingSpace(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_APPEND_TRAILING_SPACE, enabled).apply()
    }

    fun getSpeedProfile(): SpeedProfile {
        return SpeedProfile.fromId(preferences.getString(KEY_SPEED_PROFILE, SpeedProfile.BALANCED.id))
    }

    fun setSpeedProfile(profile: SpeedProfile) {
        preferences.edit()
            .putString(KEY_SPEED_PROFILE, profile.id)
            .putInt(KEY_SILENCE_HANGOVER_MS, profile.defaultSilenceHangoverMs)
            .putInt(KEY_MAX_UTTERANCE_MS, profile.defaultMaxUtteranceMs)
            .apply()
    }

    fun getAutoStopEnabled(): Boolean {
        return preferences.getBoolean(KEY_AUTO_STOP_ENABLED, false)
    }

    fun setAutoStopEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_STOP_ENABLED, enabled).apply()
    }

    fun getSilenceHangoverMs(): Int {
        val profile = getSpeedProfile()
        return preferences.getInt(KEY_SILENCE_HANGOVER_MS, profile.defaultSilenceHangoverMs)
    }

    fun setSilenceHangoverMs(valueMs: Int) {
        preferences.edit().putInt(KEY_SILENCE_HANGOVER_MS, valueMs.coerceIn(200, 5_000)).apply()
    }

    fun getMaxUtteranceMs(): Int {
        val profile = getSpeedProfile()
        return preferences.getInt(KEY_MAX_UTTERANCE_MS, profile.defaultMaxUtteranceMs)
    }

    fun setMaxUtteranceMs(valueMs: Int) {
        preferences.edit().putInt(KEY_MAX_UTTERANCE_MS, valueMs.coerceIn(1_000, 120_000)).apply()
    }

    fun getAcceleratorMode(): AcceleratorMode {
        return AcceleratorMode.fromId(
            preferences.getString(KEY_ACCELERATOR_MODE, AcceleratorMode.AUTO.id),
        )
    }

    fun setAcceleratorMode(mode: AcceleratorMode) {
        preferences.edit().putString(KEY_ACCELERATOR_MODE, mode.id).apply()
    }

    fun getWarmupEnabled(): Boolean {
        return preferences.getBoolean(KEY_WARMUP_ENABLED, true)
    }

    fun setWarmupEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WARMUP_ENABLED, enabled).apply()
    }

    fun getRuntimeSettings(): RuntimeSettings {
        val profile = getSpeedProfile()
        val storedHangover = preferences.getInt(
            KEY_SILENCE_HANGOVER_MS,
            profile.defaultSilenceHangoverMs,
        )
        val storedMaxUtterance = preferences.getInt(
            KEY_MAX_UTTERANCE_MS,
            profile.defaultMaxUtteranceMs,
        )
        return RuntimeSettings(
            speedProfile = profile,
            autoStopEnabled = getAutoStopEnabled(),
            silenceHangoverMs = storedHangover.coerceIn(200, 5_000),
            maxUtteranceMs = storedMaxUtterance.coerceIn(1_000, 120_000),
            acceleratorMode = getAcceleratorMode(),
            warmupEnabled = getWarmupEnabled(),
        )
    }

    private companion object {
        const val PREFS_NAME = "gigaam_ime_settings"
        const val KEY_ACTIVE_MODEL = "active_model"
        const val KEY_APPEND_TRAILING_SPACE = "append_trailing_space"
        const val KEY_SPEED_PROFILE = "speed_profile"
        const val KEY_AUTO_STOP_ENABLED = "auto_stop_enabled"
        const val KEY_SILENCE_HANGOVER_MS = "silence_hangover_ms"
        const val KEY_MAX_UTTERANCE_MS = "max_utterance_ms"
        const val KEY_ACCELERATOR_MODE = "accelerator_mode"
        const val KEY_WARMUP_ENABLED = "warmup_enabled"
    }
}
