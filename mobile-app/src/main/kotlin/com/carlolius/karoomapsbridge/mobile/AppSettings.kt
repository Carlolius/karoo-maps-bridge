package com.carlolius.karoomapsbridge.mobile

import android.content.Context

/** User settings of the phone app. */
object AppSettings {

    const val DEFAULT_BROUTER_SERVER = "https://brouter.de/brouter"
    private const val PREFS = "settings"
    private const val KEY_BROUTER = "brouter_server"

    /** BRouter server used to compute routes (public brouter.de unless the user set another one). */
    fun brouterServer(context: Context): String =
        prefs(context).getString(KEY_BROUTER, null) ?: DEFAULT_BROUTER_SERVER

    /** Saves a custom server; blank or invalid resets to the default. Returns what is stored. */
    fun setBrouterServer(context: Context, url: String?): String {
        val clean = normalizeServer(url)
        prefs(context).edit().apply { if (clean == null) remove(KEY_BROUTER) else putString(KEY_BROUTER, clean) }.apply()
        return clean ?: DEFAULT_BROUTER_SERVER
    }

    /** "brouter.example.org/brouter/" -> null (no scheme), "https://x/brouter/" -> "https://x/brouter". */
    fun normalizeServer(url: String?): String? {
        val trimmed = url?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty() || trimmed == DEFAULT_BROUTER_SERVER) return null
        return trimmed.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
