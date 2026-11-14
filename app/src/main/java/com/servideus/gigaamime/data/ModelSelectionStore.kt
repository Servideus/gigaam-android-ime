package com.servideus.gigaamime.data

import android.content.Context

class ModelSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAppendTrailingSpace(): Boolean = preferences.getBoolean(KEY_APPEND_TRAILING_SPACE, true)

    fun setAppendTrailingSpace(enabled: Boolean) = putBoolean(KEY_APPEND_TRAILING_SPACE, enabled)

    fun isWarmupEnabled(): Boolean = preferences.getBoolean(KEY_WARMUP_ENABLED, true)

    fun setWarmupEnabled(enabled: Boolean) = putBoolean(KEY_WARMUP_ENABLED, enabled)

    private fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()

    private companion object {
        const val PREFS_NAME = "gigaam_ime_settings"
        const val KEY_APPEND_TRAILING_SPACE = "append_trailing_space"
        const val KEY_WARMUP_ENABLED = "warmup_enabled"
    }
}
