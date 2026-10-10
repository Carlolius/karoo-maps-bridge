package com.carlolius.karoomapsbridge.karoo

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.Symbol
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.Locale

/**
 * Asks the phone for the shared points (newest first). The Karoo only makes outgoing
 * connections (incoming ones are blocked by Karoo OS, verified on a Karoo 2).
 *
 * Phone address candidates, tried in order: manual host, network gateway (= the phone
 * when the Karoo is on its hotspot), then the last phones that answered (remembered).
 */
object PointClient {

    const val PORT = 8730
    private const val TAG = "PointClient"
    private const val PREFS = "settings"
    private const val KEY_HOST = "manual_host"
    private const val KEY_KNOWN = "known_hosts"
    private const val MAX_KNOWN = 5

    data class Point(val id: String, val lat: Double, val lng: Double, val name: String?, val source: String)

    sealed class Result {
        /** Never empty. */
        data class Found(val points: List<Point>) : Result()
        data object NoPoint : Result()
        data class Unreachable(val tried: List<String>, val error: String) : Result()
    }

    fun manualHost(context: Context): String? =
        prefs(context).getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }

    fun setManualHost(context: Context, host: String?) {
        prefs(context).edit()
            .putString(KEY_HOST, host?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    fun gateway(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return null
        cm.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    }.getOrNull()

    fun knownHosts(context: Context): List<String> =
        prefs(context).getString(KEY_KNOWN, "")!!.split(',').filter { it.isNotBlank() }

    fun forgetKnownHosts(context: Context) {
        prefs(context).edit().remove(KEY_KNOWN).apply()
    }

    private fun remember(context: Context, host: String) {
        val list = (listOf(host) + knownHosts(context).filter { it != host }).take(MAX_KNOWN)
        prefs(context).edit().putString(KEY_KNOWN, list.joinToString(",")).apply()
    }

    fun candidates(context: Context): List<String> =
        LinkedHashSet(listOfNotNull(manualHost(context), gateway(context)) + knownHosts(context)).toList()

    /** Blocking network calls: use from a background thread. */
    fun fetch(context: Context): Result {
        val hosts = candidates(context)
        if (hosts.isEmpty()) return Result.Unreachable(emptyList(), "no network")
        var lastError = ""
        for (host in hosts) {
            try {
                val result = fetchFrom(host)
                remember(context, host)
                return result
            } catch (e: Exception) {
                Log.w(TAG, "No phone at $host: $e")
                lastError = e.javaClass.simpleName
            }
        }
        return Result.Unreachable(hosts, lastError)
    }

    internal fun fetchFrom(host: String): Result {
        val base = if (host.contains(':')) "http://$host" else "http://$host:$PORT"
        // v0.3+ phone app: /points. Older phone app only has /point (404 on /points).
        val json = get("$base/points") ?: get("$base/point") ?: error("not a karoo-maps-bridge server")
        val items = json.optJSONArray("points")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?: if (json.has("hasPoint")) listOf(json).filter { it.optBoolean("hasPoint") } else error("not a karoo-maps-bridge server")
        val points = items.map {
            Point(
                id = it.getString("id"),
                lat = it.getDouble("lat"),
                lng = it.getDouble("lng"),
                name = it.optString("name").takeIf { n -> !it.isNull("name") && n.isNotEmpty() },
                source = it.optString("source", "exact"),
            )
        }
        return if (points.isEmpty()) Result.NoPoint else Result.Found(points)
    }

    /** JSON body, or null on HTTP 404. Throws when nobody answers. */
    private fun get(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 3000
        }
        try {
            if (conn.responseCode == 404) return null
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Always "41.38700, 2.17009": a decimal comma would be ambiguous with the separator. */
    fun formatCoords(lat: Double, lng: Double) = String.format(Locale.ROOT, "%.5f, %.5f", lat, lng)

    /** The native "navigate to / save as POI" screen for this point. */
    fun pinDrop(point: Point) = LaunchPinDrop(
        Symbol.POI(id = point.id, lat = point.lat, lng = point.lng, name = point.name),
    )
}
