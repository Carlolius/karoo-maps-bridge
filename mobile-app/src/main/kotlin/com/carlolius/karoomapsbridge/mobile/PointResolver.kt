package com.carlolius.karoomapsbridge.mobile

import android.content.Context
import android.location.Geocoder
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Turns whatever was shared (Maps short link, directions, geo: URI, other apps' links,
 * plain address...) into one or more points. Blocking: call from a background thread.
 */
class PointResolver(private val context: Context) {

    sealed class Result {
        /**
         * [notFound]: route stops that could not be geocoded (skipped).
         * [origin]: for directions, where the route starts; null = "your location" (or not found).
         */
        data class Ok(
            val points: List<PendingPoint>,
            val isRoute: Boolean,
            val notFound: List<String> = emptyList(),
            val origin: MapsLinkParser.LatLng? = null,
        ) : Result()
        data class Failed(val reason: String) : Result()
    }

    private var lastNominatimCall = 0L

    fun resolve(shared: String): Result {
        val text = shared.trim()
        Log.i(TAG, "Resolving shared text: $text")

        val url = MapsLinkParser.extractUrl(text)
        // 1. Short link -> follow redirects to the full URL (only network call before geocoding)
        val finalUrl = url?.let {
            if (!MapsLinkParser.isShortLink(it)) it
            else runCatching { followRedirects(it) }.getOrElse { e ->
                Log.w(TAG, "Could not resolve $it", e)
                return Result.Failed(context.getString(R.string.error_link))
            }
        }
        if (finalUrl != url) Log.i(TAG, "Final URL: $finalUrl")

        // 2. Directions: every stop becomes a point, in order
        finalUrl?.let(MapsLinkParser::parseDirectionsFull)?.let { return resolveDirections(it) }

        // 3. Coordinates in the shared text or in the resolved link
        val placeText = finalUrl?.let(MapsLinkParser::placeText)
        (MapsLinkParser.parseCoords(text) ?: finalUrl?.let(MapsLinkParser::parseCoords))?.let {
            val name = MapsLinkParser.ge0Name(text) ?: nameFromText(text, placeText)
            return single(it, name, "exact")
        }

        // 4. No coordinates anywhere: geocode the name/address
        // Plain address shared from some other app (no link at all)
        val plainAddress = if (url == null) text.lines().joinToString(", ") { it.trim() }.takeIf { it.isNotBlank() } else null
        val place = placeText ?: plainAddress ?: return Result.Failed(context.getString(R.string.error_no_location))
        val (coords, source) = geocode(place) ?: return Result.Failed(context.getString(R.string.error_geocode, place))
        return single(coords, nameFromText(text, place), source)
    }

    private fun resolveDirections(directions: MapsLinkParser.Directions): Result {
        val origin = directions.origin?.let { o -> o.coords ?: o.name?.let { geocode(it)?.first } }
        val result = resolveStops(directions.stops)
        return if (result is Result.Ok) result.copy(origin = origin) else result
    }

    private fun resolveStops(stops: List<MapsLinkParser.Stop>): Result {
        val points = mutableListOf<PendingPoint>()
        val notFound = mutableListOf<String>()
        stops.forEachIndexed { i, stop ->
            val found = stop.coords?.let { it to "exact" } ?: stop.name?.let(::geocode)
            if (found == null) {
                notFound += stop.name ?: "?"
                return@forEachIndexed
            }
            val label = stop.name?.let(MapsLinkParser::displayName) ?: String.format(Locale.ROOT, "%.5f, %.5f", found.first.lat, found.first.lng)
            val name = if (stops.size == 1) label else "${i + 1}/${stops.size} · $label"
            points += PendingPoint.create(found.first.lat, found.first.lng, name, found.second)
        }
        Log.i(TAG, "Route: ${points.size} stops resolved, not found: $notFound")
        if (points.isEmpty()) return Result.Failed(context.getString(R.string.error_geocode, notFound.joinToString(", ")))
        return Result.Ok(points, isRoute = true, notFound = notFound)
    }

    private fun single(c: MapsLinkParser.LatLng, name: String?, source: String): Result {
        Log.i(TAG, "Resolved to ${c.lat},${c.lng} ($source) name=$name")
        return Result.Ok(listOf(PendingPoint.create(c.lat, c.lng, name, source)), isRoute = false)
    }

    /** Android geocoder first (precise, Google data), OpenStreetMap as fallback. */
    private fun geocode(place: String): Pair<MapsLinkParser.LatLng, String>? {
        val queries = listOfNotNull(place, MapsLinkParser.addressOnly(place))
        for (q in queries) geocodeAndroid(q)?.let { return it to "geocoded" }
        for (q in queries) geocodeNominatim(q)?.let { return it to "approximate" }
        return null
    }

    private fun nameFromText(text: String, placeText: String?): String? =
        placeText?.let(MapsLinkParser::displayName)
            ?: text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && MapsLinkParser.extractUrl(it) == null && MapsLinkParser.parseCoords(it) == null }

    private fun followRedirects(start: String): String {
        var current = start
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code !in 300..399 || location == null) return current
                current = URL(URL(current), location).toString()
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    /** Android's built-in geocoder (Google data on phones with Play Services, no API key). */
    @Suppress("DEPRECATION")
    private fun geocodeAndroid(query: String): MapsLinkParser.LatLng? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context).getFromLocationName(query, 1)?.firstOrNull()?.let { MapsLinkParser.LatLng(it.latitude, it.longitude) }
        }.onFailure { Log.w(TAG, "Android geocoder failed for '$query'", it) }.getOrNull()
    }

    /** OpenStreetMap fallback. Usage policy: identify the app, max 1 request per second. */
    private fun geocodeNominatim(query: String): MapsLinkParser.LatLng? = runCatching {
        val wait = lastNominatimCall + NOMINATIM_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastNominatimCall = System.currentTimeMillis()
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val results = JSONArray(conn.inputStream.bufferedReader().readText())
            if (results.length() == 0) null
            else results.getJSONObject(0).let { MapsLinkParser.LatLng(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "Nominatim failed for '$query'", it) }.getOrNull()

    companion object {
        private const val TAG = "PointResolver"
        private const val MAX_REDIRECTS = 6
        private const val TIMEOUT_MS = 8000
        private const val NOMINATIM_INTERVAL_MS = 1100L
        private const val USER_AGENT = "karoo-maps-bridge/1.0 (+https://github.com/Carlolius/karoo-maps-bridge)"
    }
}
