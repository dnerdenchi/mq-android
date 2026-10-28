package com.example.glorytun

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest

object AdGuardAutomation {
    private val stateLock = Any()
    private const val TAG = "AdGuardAutomation"
    private const val ADGUARD_PACKAGE = "com.adguard.android"
    private const val ADGUARD_RECEIVER = "com.adguard.android.receiver.AutomationReceiver"
    private const val ACTION_PROXY_STATE = "proxy_state"
    private const val KEY_PASSWORD = "automation_password"
    private const val KEY_LAST_OFF_PASSWORD_HASH = "last_off_password_hash"

    fun isEnabled(context: Context): Boolean = synchronized(stateLock) {
        isEnabledLocked(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) = synchronized(stateLock) {
        val preferences = proxyPreferences(context)
        val saved = preferences.edit()
            .putBoolean(GlorytunConstants.KEY_ADGUARD_AUTO_PROXY_ENABLED, enabled)
            .commit()
        if (!saved) {
            Log.e(TAG, "Could not persist AdGuard automation setting")
        } else if (!enabled && isProxyActiveLocked(context)) {
            setProxyEnabledLocked(context, enabled = false)
        }
        Unit
    }

    fun hasPassword(context: Context): Boolean = synchronized(stateLock) {
        runCatching {
            securePreferences(context).getString(KEY_PASSWORD, null)?.isNotBlank() == true
        }.onFailure { error ->
            Log.e(TAG, "Could not read AdGuard automation password state", error)
        }.getOrDefault(false)
    }

    fun savePassword(context: Context, password: String): Boolean = synchronized(stateLock) {
        val saved = runCatching {
            securePreferences(context).edit().putString(KEY_PASSWORD, password).commit()
        }.onFailure { error ->
            Log.e(TAG, "Could not save AdGuard automation password", error)
        }.getOrDefault(false)
        if (saved && !isProxyActiveLocked(context) && isRecoveryPendingLocked(context)) {
            setProxyEnabledLocked(context, enabled = false)
        }
        saved
    }

    fun isProxyActive(context: Context): Boolean = synchronized(stateLock) {
        isProxyActiveLocked(context)
    }

    fun enableProxyForSession(
        context: Context,
        automationRequested: Boolean,
        sessionIsActive: () -> Boolean,
    ): Boolean = synchronized(stateLock) {
        val sessionActive = sessionIsActive()
        val settingEnabled = isEnabledLocked(context)
        if (!automationRequested || !sessionActive || !settingEnabled) {
            Log.w(
                TAG,
                "Skipping AdGuard proxy enable: requested=$automationRequested, " +
                    "sessionActive=$sessionActive, settingEnabled=$settingEnabled",
            )
            return@synchronized false
        }
        setProxyEnabledLocked(context, enabled = true)
    }

    fun setProxyEnabled(context: Context, enabled: Boolean): Boolean = synchronized(stateLock) {
        setProxyEnabledLocked(context, enabled)
    }

    private fun setProxyEnabledLocked(context: Context, enabled: Boolean): Boolean {
        val preferences = proxyPreferences(context)
        val securePreferences = runCatching { securePreferences(context) }.getOrElse { error ->
            Log.e(TAG, "Could not read AdGuard automation password", error)
            return false
        }
        val password = securePreferences.getString(KEY_PASSWORD, null)
        if (password.isNullOrBlank()) {
            Log.w(TAG, "AdGuard automation password is not configured")
            return false
        }

        if (enabled) {
            // Keep a recovery marker before sending, so a process death can be repaired later.
            val saved = preferences.edit()
                .putBoolean(GlorytunConstants.KEY_ADGUARD_AUTOMATION_ACTIVE, true)
                .putBoolean(GlorytunConstants.KEY_ADGUARD_AUTOMATION_RECOVERY_PENDING, true)
                .remove(KEY_LAST_OFF_PASSWORD_HASH)
                .commit()
            if (!saved) {
                Log.e(TAG, "Could not persist AdGuard automation recovery state")
                return false
            }
        }

        return runCatching {
            context.sendBroadcast(Intent(ACTION_PROXY_STATE).apply {
                setClassName(ADGUARD_PACKAGE, ADGUARD_RECEIVER)
                putExtra("password", password)
                putExtra("enable", enabled)
                putExtra("quiet", true)
            })
            Log.i(TAG, "Dispatched AdGuard proxy state request: enable=$enabled")
            if (!enabled) {
                val saved = preferences.edit()
                    .putBoolean(GlorytunConstants.KEY_ADGUARD_AUTOMATION_ACTIVE, false)
                    .putBoolean(GlorytunConstants.KEY_ADGUARD_AUTOMATION_RECOVERY_PENDING, true)
                    .putString(KEY_LAST_OFF_PASSWORD_HASH, passwordHash(password))
                    .commit()
                if (!saved) {
                    Log.e(TAG, "Could not persist AdGuard automation recovery state")
                    return false
                }
            }
            true
        }.onFailure { error ->
            Log.e(TAG, "Could not send AdGuard proxy state", error)
        }.getOrDefault(false)
    }

    fun restoreProxyOffIfNeeded(context: Context): Boolean = synchronized(stateLock) {
        val preferences = proxyPreferences(context)
        val isActive = isProxyActiveLocked(context)
        val isPending = isRecoveryPendingLocked(context)
        if (!isActive && !isPending) return@synchronized false

        val password = runCatching {
            securePreferences(context).getString(KEY_PASSWORD, null)
        }.onFailure { error ->
            Log.e(TAG, "Could not read AdGuard automation password for recovery", error)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return@synchronized false

        val lastOffPasswordHash = preferences.getString(KEY_LAST_OFF_PASSWORD_HASH, null)
        if (!isActive && isPending && lastOffPasswordHash == passwordHash(password)) {
            return@synchronized false
        }
        setProxyEnabledLocked(context, enabled = false)
    }

    private fun isEnabledLocked(context: Context): Boolean = proxyPreferences(context)
        .getBoolean(GlorytunConstants.KEY_ADGUARD_AUTO_PROXY_ENABLED, false)

    private fun isProxyActiveLocked(context: Context): Boolean = proxyPreferences(context)
        .getBoolean(GlorytunConstants.KEY_ADGUARD_AUTOMATION_ACTIVE, false)

    private fun isRecoveryPendingLocked(context: Context): Boolean = proxyPreferences(context)
        .getBoolean(GlorytunConstants.KEY_ADGUARD_AUTOMATION_RECOVERY_PENDING, false)

    private fun passwordHash(password: String): String = MessageDigest.getInstance("SHA-256")
        .digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun proxyPreferences(context: Context): SharedPreferences = context
        .getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)

    private fun securePreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            GlorytunConstants.PREFS_ADGUARD_AUTOMATION,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
