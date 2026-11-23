package com.example.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class SpeedUnit {
    MBPS,
    MB_PER_SEC,
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val speedUnit: SpeedUnit = SpeedUnit.MBPS,
    val liteMode: Boolean = false,
    val hapticFeedback: Boolean = true,
    val mobileDataWarning: Boolean = true,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "speedtest_settings",
)

/**
 * DataStore-backed settings repository for user preferences.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val LITE_MODE = booleanPreferencesKey("lite_mode")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val MOBILE_DATA_WARNING = booleanPreferencesKey("mobile_data_warning")
    }

    val settings: Flow<UserSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserSettings(
                themeMode = prefs[Keys.THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                speedUnit = prefs[Keys.SPEED_UNIT]
                    ?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() }
                    ?: SpeedUnit.MBPS,
                liteMode = prefs[Keys.LITE_MODE] ?: false,
                hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
                mobileDataWarning = prefs[Keys.MOBILE_DATA_WARNING] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setSpeedUnit(unit: SpeedUnit) {
        dataStore.edit { it[Keys.SPEED_UNIT] = unit.name }
    }

    suspend fun setLiteMode(enabled: Boolean) {
        dataStore.edit { it[Keys.LITE_MODE] = enabled }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        dataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setMobileDataWarning(enabled: Boolean) {
        dataStore.edit { it[Keys.MOBILE_DATA_WARNING] = enabled }
    }
}
