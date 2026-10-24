package com.kevinushey.quell.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Settings are cheap to lose and fatal to crash on: a damaged file is replaced, not fought over.
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val analysisKey = stringPreferencesKey("analysis")
    private val appKey = stringPreferencesKey("app")

    val analysis: Flow<AnalysisSettings> = context.dataStore.data
        .map { prefs ->
            prefs[analysisKey]?.let { runCatching { json.decodeFromString<AnalysisSettings>(it) }.getOrNull() }
                ?: AnalysisSettings()
        }
        .catch { emit(AnalysisSettings()) }

    val app: Flow<AppSettings> = context.dataStore.data
        .map { prefs ->
            prefs[appKey]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
                ?: AppSettings()
        }
        .catch { emit(AppSettings()) }

    suspend fun saveAnalysis(settings: AnalysisSettings) {
        context.dataStore.edit { it[analysisKey] = json.encodeToString(settings) }
    }

    suspend fun saveApp(settings: AppSettings) {
        context.dataStore.edit { it[appKey] = json.encodeToString(settings) }
    }
}
