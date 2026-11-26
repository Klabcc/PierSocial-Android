package com.klabcc.piersocial.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPreferencesDataStore by preferencesDataStore(name = "pier_app_preferences")

class AppPreferencesManager(private val context: Context) {
    private val profileSetupCompletedKey = booleanPreferencesKey("profile_setup_completed")
    private val developerModeKey = booleanPreferencesKey("developer_mode_enabled")
    private val dmNotificationsEnabledKey = booleanPreferencesKey("dm_notifications_enabled")

    val profileSetupCompletedFlow: Flow<Boolean> = context.appPreferencesDataStore.data.map { prefs ->
        prefs[profileSetupCompletedKey] ?: false
    }

    val developerModeFlow: Flow<Boolean> = context.appPreferencesDataStore.data.map { prefs ->
        prefs[developerModeKey] ?: false
    }

    val dmNotificationsEnabledFlow: Flow<Boolean> = context.appPreferencesDataStore.data.map { prefs ->
        prefs[dmNotificationsEnabledKey] ?: true
    }

    suspend fun setProfileSetupCompleted(completed: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[profileSetupCompletedKey] = completed
        }
    }

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[developerModeKey] = enabled
        }
    }

    suspend fun setDmNotificationsEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[dmNotificationsEnabledKey] = enabled
        }
    }
}
