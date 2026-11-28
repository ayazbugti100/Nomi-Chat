package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AuthFlowMode
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.nomiDataStore: DataStore<Preferences> by preferencesDataStore(name = "nomi_chat_prefs")

data class UserAppPreferences(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val notificationsEnabled: Boolean = true,
    val smartAiEnabled: Boolean = true,
    val preferredDisplayName: String = "",
    val preferredUsername: String = "",
    val preferredDob: String = "",
    val authFlowMode: AuthFlowMode = AuthFlowMode.GOOGLE_QUICK,
    val returningIdentifier: String = "",
    val activeUsername: String = "",
    val activeUserId: String = "",
    val appLockPinHash: String = ""
) {
    val isAppLockEnabled: Boolean get() = appLockPinHash.isNotBlank()
}

class UserPreferencesRepository(private val context: Context) {
    private val dataStore = context.applicationContext.nomiDataStore

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SMART_AI_ENABLED = booleanPreferencesKey("smart_ai_enabled")
        val PREFERRED_NAME = stringPreferencesKey("preferred_display_name")
        val PREFERRED_USERNAME = stringPreferencesKey("preferred_username")
        val PREFERRED_DOB = stringPreferencesKey("preferred_dob")
        val AUTH_FLOW_MODE = stringPreferencesKey("auth_flow_mode")
        val RETURNING_IDENTIFIER = stringPreferencesKey("returning_identifier")
        val ACTIVE_USERNAME = stringPreferencesKey("active_username")
        val ACTIVE_USER_ID = stringPreferencesKey("active_user_id")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
    }

    val preferencesFlow: Flow<UserAppPreferences> = dataStore.data.map { prefs ->
        val themeRaw = prefs[Keys.THEME_MODE] ?: ThemeMode.LIGHT.name
        val themeMode = ThemeMode.entries.firstOrNull { it.name == themeRaw } ?: ThemeMode.LIGHT
        val modeRaw = prefs[Keys.AUTH_FLOW_MODE] ?: AuthFlowMode.GOOGLE_QUICK.name
        val authMode = AuthFlowMode.entries.firstOrNull { it.name == modeRaw } ?: AuthFlowMode.GOOGLE_QUICK
        UserAppPreferences(
            themeMode = themeMode,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            smartAiEnabled = prefs[Keys.SMART_AI_ENABLED] ?: true,
            preferredDisplayName = prefs[Keys.PREFERRED_NAME] ?: "",
            preferredUsername = prefs[Keys.PREFERRED_USERNAME] ?: "",
            preferredDob = prefs[Keys.PREFERRED_DOB] ?: "",
            authFlowMode = authMode,
            returningIdentifier = prefs[Keys.RETURNING_IDENTIFIER] ?: "",
            activeUsername = prefs[Keys.ACTIVE_USERNAME] ?: "",
            activeUserId = prefs[Keys.ACTIVE_USER_ID] ?: "",
            appLockPinHash = prefs[Keys.APP_LOCK_PIN_HASH] ?: ""
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setSmartAiEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SMART_AI_ENABLED] = enabled }
    }

    suspend fun savePreferredSignInIdentity(
        displayName: String,
        username: String,
        dob: String = "",
        authFlowMode: AuthFlowMode = AuthFlowMode.SIGN_UP,
        returningIdentifier: String = ""
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_NAME] = displayName.trim()
            prefs[Keys.PREFERRED_USERNAME] = username.trim().lowercase().replace(Regex("\\s+"), "")
            prefs[Keys.PREFERRED_DOB] = dob.trim()
            prefs[Keys.AUTH_FLOW_MODE] = authFlowMode.name
            prefs[Keys.RETURNING_IDENTIFIER] = returningIdentifier.trim()
        }
    }

    suspend fun saveActiveUsername(username: String) {
        dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_USERNAME] = username.trim().lowercase()
        }
    }

    suspend fun saveActiveSession(userId: String, username: String) {
        dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_USER_ID] = userId.trim()
            prefs[Keys.ACTIVE_USERNAME] = username.trim().lowercase()
        }
    }

    suspend fun clearPendingSignInFlow() {
        dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_NAME] = ""
            prefs[Keys.PREFERRED_USERNAME] = ""
            prefs[Keys.PREFERRED_DOB] = ""
            prefs[Keys.AUTH_FLOW_MODE] = AuthFlowMode.GOOGLE_QUICK.name
            prefs[Keys.RETURNING_IDENTIFIER] = ""
        }
    }

    suspend fun applyAccountSettings(
        themeMode: ThemeMode,
        notificationsEnabled: Boolean,
        smartAiEnabled: Boolean,
        appLockPinHash: String
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = themeMode.name
            prefs[Keys.NOTIFICATIONS_ENABLED] = notificationsEnabled
            prefs[Keys.SMART_AI_ENABLED] = smartAiEnabled
            prefs[Keys.APP_LOCK_PIN_HASH] = appLockPinHash
        }
    }

    suspend fun setAppLockPin(pin: String): String {
        val hash = if (pin.isBlank()) "" else sha256(pin)
        dataStore.edit { it[Keys.APP_LOCK_PIN_HASH] = hash }
        return hash
    }

    fun verifyAppLockPin(pin: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return true
        return sha256(pin) == storedHash
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
