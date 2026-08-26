package com.example.utils

import android.content.Context
import android.content.SharedPreferences

object VaultUtils {
    private const val PREFS_NAME = "vault_prefs"
    private const val KEY_PIN = "vault_pin"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPinSet(context: Context): Boolean {
        val pin = getPin(context)
        return !pin.isNullOrEmpty()
    }

    fun getPin(context: Context): String? {
        return getPrefs(context).getString(KEY_PIN, null)
    }

    fun savePin(context: Context, pin: String): Boolean {
        return getPrefs(context).edit().putString(KEY_PIN, pin).commit()
    }

    fun clearPin(context: Context) {
        getPrefs(context).edit().remove(KEY_PIN).apply()
    }
}
