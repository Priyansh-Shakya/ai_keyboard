package com.aikeyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores only the login session (encrypted). No API keys live in the app anymore. */
object PrefsManager {
    private const val FILE = "ai_keyboard_session"
    @Volatile private var prefs: SharedPreferences? = null

    private fun create(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun p(context: Context): SharedPreferences {
        val ctx = context.applicationContext
        return prefs ?: synchronized(this) {
            prefs ?: try {
                create(ctx)
            } catch (e: Exception) {
                ctx.deleteSharedPreferences(FILE)   // corrupted keystore state: start clean
                create(ctx)
            }.also { prefs = it }
        }
    }

    fun saveSession(ctx: Context, s: Session) {
        p(ctx).edit()
            .putString("access", s.accessToken)
            .putString("refresh", s.refreshToken)
            .putLong("expires", s.expiresAt)
            .putString("email", s.email)
            .apply()
    }

    fun getSession(ctx: Context): Session? {
        val sp = p(ctx)
        val access = sp.getString("access", null) ?: return null
        val refresh = sp.getString("refresh", null) ?: return null
        return Session(access, refresh, sp.getLong("expires", 0L), sp.getString("email", "") ?: "")
    }

    fun clearSession(ctx: Context) = p(ctx).edit().clear().apply()

    fun isLoggedIn(ctx: Context) = try { getSession(ctx) != null } catch (e: Exception) { false }

    fun setVibrationEnabled(ctx: Context, enabled: Boolean) {
        p(ctx).edit().putBoolean("vibration", enabled).apply()
    }

    fun isVibrationEnabled(ctx: Context): Boolean {
        return p(ctx).getBoolean("vibration", false)
    }

    fun getClipboardHistory(ctx: Context): List<String> {
        val json = p(ctx).getString("clipboard_history", "[]") ?: "[]"
        return try {
            com.google.gson.Gson().fromJson(json, Array<String>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveClipboardHistory(ctx: Context, history: List<String>) {
        val json = com.google.gson.Gson().toJson(history.take(100))
        p(ctx).edit().putString("clipboard_history", json).apply()
    }

    fun addClipboardClip(ctx: Context, text: String) {
        if (text.isBlank()) return
        val current = getClipboardHistory(ctx).toMutableList()
        current.remove(text)
        current.add(0, text)
        saveClipboardHistory(ctx, current)
    }

    fun removeClipboardClip(ctx: Context, text: String) {
        val current = getClipboardHistory(ctx).toMutableList()
        current.remove(text)
        saveClipboardHistory(ctx, current)
    }

    fun clearClipboardHistory(ctx: Context) {
        p(ctx).edit().remove("clipboard_history").apply()
    }
}
