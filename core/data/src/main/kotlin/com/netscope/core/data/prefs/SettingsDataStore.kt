package com.netscope.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.netScopeDataStore by preferencesDataStore(name = "netscope_settings")

enum class ThemeMode {
  SYSTEM,
  DARK,
  LIGHT,
}

data class UserSettings(
  val onboardingCompleted: Boolean = false,
  val vpnConsentAccepted: Boolean = false,
  val themeMode: ThemeMode = ThemeMode.DARK,
  val retentionDays: Int = 7,
  val autoCleanupEnabled: Boolean = true,
  val pinningCompatibilityMode: Boolean = true,
  val overlayFilterPackage: String = "",
  val fridaHost: String = "127.0.0.1",
  val fridaPort: Int = 27042,
)

class SettingsDataStore(private val context: Context) {
  private object Keys {
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val VPN_CONSENT_ACCEPTED = booleanPreferencesKey("vpn_consent_accepted")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val RETENTION_DAYS = intPreferencesKey("retention_days")
    val AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup")
    val PINNING_COMPAT_MODE = booleanPreferencesKey("pinning_compat_mode")
    val OVERLAY_FILTER_PACKAGE = stringPreferencesKey("overlay_filter_package")
    val FRIDA_HOST = stringPreferencesKey("frida_host")
    val FRIDA_PORT = intPreferencesKey("frida_port")
  }

  val settings: Flow<UserSettings> =
    context.netScopeDataStore.data.map { prefs ->
      UserSettings(
        onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
        vpnConsentAccepted = prefs[Keys.VPN_CONSENT_ACCEPTED] ?: false,
        themeMode =
          runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name) }
            .getOrDefault(ThemeMode.DARK),
        retentionDays = prefs[Keys.RETENTION_DAYS] ?: 7,
        autoCleanupEnabled = prefs[Keys.AUTO_CLEANUP] ?: true,
        pinningCompatibilityMode = prefs[Keys.PINNING_COMPAT_MODE] ?: true,
        overlayFilterPackage = prefs[Keys.OVERLAY_FILTER_PACKAGE] ?: "",
        fridaHost = prefs[Keys.FRIDA_HOST] ?: "127.0.0.1",
        fridaPort = prefs[Keys.FRIDA_PORT] ?: 27042,
      )
    }

  suspend fun setOnboardingCompleted(completed: Boolean) {
    context.netScopeDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
  }

  suspend fun setVpnConsentAccepted(accepted: Boolean) {
    context.netScopeDataStore.edit { it[Keys.VPN_CONSENT_ACCEPTED] = accepted }
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.netScopeDataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }

  suspend fun setRetentionDays(days: Int) {
    context.netScopeDataStore.edit { it[Keys.RETENTION_DAYS] = days.coerceIn(1, 90) }
  }

  suspend fun setAutoCleanupEnabled(enabled: Boolean) {
    context.netScopeDataStore.edit { it[Keys.AUTO_CLEANUP] = enabled }
  }

  suspend fun setPinningCompatibilityMode(enabled: Boolean) {
    context.netScopeDataStore.edit { it[Keys.PINNING_COMPAT_MODE] = enabled }
  }

  suspend fun setOverlayFilterPackage(pkg: String) {
    context.netScopeDataStore.edit { it[Keys.OVERLAY_FILTER_PACKAGE] = pkg }
  }

  suspend fun setFridaEndpoint(host: String, port: Int) {
    context.netScopeDataStore.edit {
      it[Keys.FRIDA_HOST] = host.ifBlank { "127.0.0.1" }
      it[Keys.FRIDA_PORT] = port.coerceIn(1, 65535)
    }
  }
}
