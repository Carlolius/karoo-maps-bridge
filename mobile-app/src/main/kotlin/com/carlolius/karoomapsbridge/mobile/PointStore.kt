package com.carlolius.karoomapsbridge.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A point shared from a map app, waiting for the Karoo to fetch it.
 * [source]: "exact" (coordinates in the link), "geocoded" (Android geocoder)
 * or "approximate" (OpenStreetMap geocoder, may be street-level only).
 */
data class PendingPoint(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String?,
    val source: String,
    val timestamp: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("hasPoint", true)
        .put("id", id)
        .put("lat", lat)
        .put("lng", lng)
        .put("name", name ?: JSONObject.NULL)
        .put("source", source)
        .put("timestamp", timestamp)

    companion object {
        fun create(lat: Double, lng: Double, name: String?, source: String) =
            PendingPoint(UUID.randomUUID().toString(), lat, lng, name, source, System.currentTimeMillis())

        fun fromJson(json: JSONObject) = PendingPoint(
            id = json.getString("id"),
            lat = json.getDouble("lat"),
            lng = json.getDouble("lng"),
            name = json.optString("name").takeIf { !json.isNull("name") && it.isNotEmpty() },
            source = json.optString("source", "exact"),
            timestamp = json.getLong("timestamp"),
        )
    }
}

/**
 * Newest-first list of shared points (a shared route adds its stops as one block, in order).
 * Persisted in SharedPreferences so it survives the app/service being killed.
 */
object PointStore {
    const val MAX_POINTS = 20
    private const val PREFS = "points"
    private const val KEY_LIST = "list"
    private const val KEY_LEGACY = "pending" // v0.1/v0.2: single point
    private const val KEY_UNRECOGNIZED = "unrecognized"

    @Synchronized
    fun all(context: Context): List<PendingPoint> {
        val prefs = prefs(context)
        prefs.getString(KEY_LEGACY, null)?.let { legacy ->
            val migrated = runCatching { PendingPoint.fromJson(JSONObject(legacy)) }.getOrNull()
            write(context, listOfNotNull(migrated))
            prefs.edit().remove(KEY_LEGACY).apply()
        }
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { PendingPoint.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun latest(context: Context): PendingPoint? = all(context).firstOrNull()

    @Synchronized
    fun addAll(context: Context, points: List<PendingPoint>) {
        val ids = points.map { it.id }.toSet()
        write(context, (points + all(context).filter { it.id !in ids }).take(MAX_POINTS))
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, all(context).filter { it.id != id })
    }

    @Synchronized
    fun clear(context: Context) {
        write(context, emptyList())
    }

    /** Last shared text that could not be turned into a location, to show/copy for diagnosis. */
    fun lastUnrecognized(context: Context): String? = prefs(context).getString(KEY_UNRECOGNIZED, null)

    fun setLastUnrecognized(context: Context, text: String?) {
        prefs(context).edit().putString(KEY_UNRECOGNIZED, text?.take(2000)).apply()
    }

    private fun write(context: Context, points: List<PendingPoint>) {
        val array = JSONArray().apply { points.forEach { put(it.toJson()) } }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
