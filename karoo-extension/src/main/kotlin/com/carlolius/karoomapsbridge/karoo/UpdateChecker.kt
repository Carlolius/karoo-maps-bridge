package com.carlolius.karoomapsbridge.karoo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sideloaded apps don't update themselves: look at the latest GitHub release (at most once a
 * day) and tell the user when there is a newer version. Only contacts api.github.com.
 */
object UpdateChecker {

    const val RELEASES_URL = "https://github.com/Carlolius/karoo-maps-bridge/releases/latest"
    private const val API_URL = "https://api.github.com/repos/Carlolius/karoo-maps-bridge/releases/latest"
    private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LATEST = "latest"

    /** Version numbers compared one by one: "v1.10.0" > "1.9.2". Non-numeric parts count as 0. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Newer version from the last check, without network. */
    fun cachedUpdate(context: Context): String? =
        prefs(context).getString(KEY_LATEST, null)?.takeIf { isNewer(it, currentVersion(context)) }

    /** Blocking: asks GitHub if the last check is older than a day. Returns a newer version or null. */
    fun check(context: Context): String? {
        val prefs = prefs(context)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return cachedUpdate(context)
        runCatching {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "karoo-maps-bridge/${currentVersion(context)}")
            }
            try {
                if (conn.responseCode == 200) {
                    val tag = JSONObject(conn.inputStream.bufferedReader().readText()).getString("tag_name")
                    prefs.edit().putString(KEY_LATEST, tag.removePrefix("v")).putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w("UpdateChecker", "Update check failed", it) }
        return cachedUpdate(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
