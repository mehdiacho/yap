package com.mehdiacho.yap.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings persistence — SharedPreferences backed, exposed as StateFlows so Compose
 * screens observe live values. Call [init] once (Application/MainActivity onCreate) before use.
 *
 * Owns every toggle from the Settings design pass: capture behavior, privacy, appearance,
 * transcription, and the hidden Advanced section.
 */
object SettingsStore {
    private const val PREFS = "yap_settings"
    private lateinit var prefs: SharedPreferences

    // Capture
    private val _autoStopEnabled = MutableStateFlow(true)
    private val _autoStopSecs = MutableStateFlow(30)
    private val _haptics = MutableStateFlow(true)
    private val _sounds = MutableStateFlow(false)
    // Privacy
    private val _appLock = MutableStateFlow(false)
    private val _retention = MutableStateFlow("keep") // keep | discard | auto30
    private val _onDeviceOnly = MutableStateFlow(false)
    // Appearance
    private val _themeMode = MutableStateFlow("system") // system | light | dark
    private val _dynamicColor = MutableStateFlow(false)
    // Transcription
    private val _vttExport = MutableStateFlow(false)
    // Advanced (About, 7-tap unlock)
    private val _advancedUnlocked = MutableStateFlow(false)
    private val _experimentalModel = MutableStateFlow(false)
    private val _verboseLogs = MutableStateFlow(false)

    val autoStopEnabled: StateFlow<Boolean> = _autoStopEnabled.asStateFlow()
    val autoStopSecs: StateFlow<Int> = _autoStopSecs.asStateFlow()
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()
    val appLock: StateFlow<Boolean> = _appLock.asStateFlow()
    val retention: StateFlow<String> = _retention.asStateFlow()
    val onDeviceOnly: StateFlow<Boolean> = _onDeviceOnly.asStateFlow()
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()
    val vttExport: StateFlow<Boolean> = _vttExport.asStateFlow()
    val advancedUnlocked: StateFlow<Boolean> = _advancedUnlocked.asStateFlow()
    val experimentalModel: StateFlow<Boolean> = _experimentalModel.asStateFlow()
    val verboseLogs: StateFlow<Boolean> = _verboseLogs.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _autoStopEnabled.value = prefs.getBoolean("auto_stop_enabled", true)
        _autoStopSecs.value = prefs.getInt("auto_stop_secs", 30)
        _haptics.value = prefs.getBoolean("haptics", true)
        _sounds.value = prefs.getBoolean("sounds", false)
        _appLock.value = prefs.getBoolean("app_lock", false)
        _retention.value = prefs.getString("retention", "keep") ?: "keep"
        _onDeviceOnly.value = prefs.getBoolean("on_device_only", false)
        _themeMode.value = prefs.getString("theme_mode", "system") ?: "system"
        _dynamicColor.value = prefs.getBoolean("dynamic_color", false)
        _vttExport.value = prefs.getBoolean("vtt_export", false)
        _advancedUnlocked.value = prefs.getBoolean("advanced_unlocked", false)
        _experimentalModel.value = prefs.getBoolean("experimental_model", false)
        _verboseLogs.value = prefs.getBoolean("verbose_logs", false)
    }

    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    private fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun putString(key: String, v: String) = prefs.edit().putString(key, v).apply()

    fun setAutoStopEnabled(v: Boolean) { _autoStopEnabled.value = v; putBool("auto_stop_enabled", v) }
    fun setAutoStopSecs(v: Int) { _autoStopSecs.value = v; putInt("auto_stop_secs", v) }
    fun setHaptics(v: Boolean) { _haptics.value = v; putBool("haptics", v) }
    fun setSounds(v: Boolean) { _sounds.value = v; putBool("sounds", v) }
    fun setAppLock(v: Boolean) { _appLock.value = v; putBool("app_lock", v) }
    fun setRetention(v: String) { _retention.value = v; putString("retention", v) }
    fun setOnDeviceOnly(v: Boolean) { _onDeviceOnly.value = v; putBool("on_device_only", v) }
    fun setThemeMode(v: String) { _themeMode.value = v; putString("theme_mode", v) }
    fun setDynamicColor(v: Boolean) { _dynamicColor.value = v; putBool("dynamic_color", v) }
    fun setVttExport(v: Boolean) { _vttExport.value = v; putBool("vtt_export", v) }
    fun setAdvancedUnlocked(v: Boolean) { _advancedUnlocked.value = v; putBool("advanced_unlocked", v) }
    fun setExperimentalModel(v: Boolean) { _experimentalModel.value = v; putBool("experimental_model", v) }
    fun setVerboseLogs(v: Boolean) { _verboseLogs.value = v; putBool("verbose_logs", v) }
}
