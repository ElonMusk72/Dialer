package com.example.utils

import android.content.Context
import android.content.SharedPreferences

object VaultUtils {
    private const val TAG = "VaultUtils"
    private const val PREFS_NAME = "vault_prefs"
    private const val KEY_PIN = "vault_pin"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPinSet(context: Context): Boolean {
        val pin = getPin(context)
        val isSet = !pin.isNullOrEmpty()
        LogRecorder.logDebug(TAG, "isPinSet: $isSet")
        return isSet
    }

    fun getPin(context: Context): String? {
        val pin = getPrefs(context).getString(KEY_PIN, null)
        LogRecorder.logDebug(TAG, "getPin retrieved: ${if (pin != null) "****" else "null"}")
        return pin
    }

    fun savePin(context: Context, pin: String): Boolean {
        val saved = getPrefs(context).edit().putString(KEY_PIN, pin).commit()
        if (saved) {
            LogRecorder.logSuccess(TAG, "Vault PIN saved successfully")
        } else {
            LogRecorder.logError(TAG, "Failed to save Vault PIN")
        }
        return saved
    }

    fun clearPin(context: Context) {
        getPrefs(context).edit().remove(KEY_PIN).apply()
        LogRecorder.logInfo(TAG, "Vault PIN cleared")
    }
}
