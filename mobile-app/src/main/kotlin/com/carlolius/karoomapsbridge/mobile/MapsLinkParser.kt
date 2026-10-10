package com.carlolius.karoomapsbridge.mobile

import java.net.URLDecoder

/**
 * Pure parsing of what map apps share. No network, no Android.
 *
 * Google Maps shares short links (maps.app.goo.gl/...). Once resolved:
 *  - dropped pins carry exact coordinates as `!3d<lat>!4d<lng>`
 *  - businesses/addresses usually carry NO coordinates, only `/maps/place/<name, address>`,
 *    so those have to be geocoded (see [PointResolver])
 *  - directions (`/maps/dir/A/B/C`) carry the stops, never the route geometry
 *
 * Other apps: geo: URIs, OpenStreetMap, OsmAnd, Apple Maps, Waze, and Organic Maps /
 * MAPS.ME `ge0` links (omaps.app, ge0.me, maps.me/link/ge0, om://).
 */
object MapsLinkParser {

    data class LatLng(val lat: Double, val lng: Double)

    /** A place with coordinates, a name to geocode, or both. */
    data class Stop(val name: String?, val coords: LatLng?)

    private const val NUM = """(-?\d{1,3}(?:\.\d+)?)"""
    private const val SEP = """\s*(?:,|%2C)\s*"""

    private val urlRegex = Regex("""(?:https?|om|ge0)://\S+""")
    private val shortLinkHosts = listOf("maps.app.goo.gl", "goo.gl")

    // Ordered from most to least precise. Each returns (lat, lng) or null.
    private val coordFinders: List<(String) -> LatLng?> = listOf(
        pair(Regex("""^geo:$NUM$SEP$NUM""")),                                  // geo:lat,lng
        pair(Regex("""[?&]q=$NUM$SEP$NUM""")),                                 // geo:0,0?q=lat,lng / ?q=lat,lng
        pair(Regex("""!3d$NUM!4d$NUM""")),                                      // Google place blob (exact pin)
        ::ge0,                                                                  // Organic Maps / MAPS.ME
        pair(Regex("""[?&](?:ll|query|destination|daddr|coordinate|pin)=$NUM$SEP$NUM""")), // Apple, Waze, OsmAnd
        separate("mlat", "mlon"),                                               // openstreetmap.org marker
        separate("lat", "lon"), separate("lat", "lng"),                         // OsmAnd go?lat=&lon=, others
        pair(Regex("""^\s*$NUM$SEP$NUM\s*$""")),                                // bare "lat, lng"
        pair(Regex("""@$NUM,$NUM""")),                                          // Google viewport centre
        pair(Regex("""#(?:map=)?\d{1,2}(?:\.\d+)?/$NUM/$NUM""")),              // OSM / OsmAnd #zoom/lat/lon
    )

    fun extractUrl(text: String): String? =
        urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']')

    fun isShortLink(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return shortLinkHosts.any { host == it }
    }

    fun parseCoords(text: String): LatLng? = coordFinders.firstNotNullOfOrNull { it(text) }

    /** "Name, street, city" from `/maps/place/<...>/` or `/maps/search/<...>/`, decoded. */
    fun placeText(url: String): String? {
        val m = Regex("""/maps/(?:place|search)/([^/?#]+)""").find(url) ?: return null
        return decode(m.groupValues[1])?.trim()?.takeIf { it.isNotEmpty() && parseCoords(it) == null }
    }

    /** Point name embedded in a ge0 / omaps.app link, if any. */
    fun ge0Name(text: String): String? =
        ge0Regex.find(text)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.let(::decodeGe0Name)

    /**
     * A directions link: [origin] is null when it was "your location" (not in the link);
     * [stops] are the intermediate stops and the destination, in order.
     */
    data class Directions(val origin: Stop?, val stops: List<Stop>)

    /**
     * Stops of a Google Maps directions link, origin excluded (it's where you are).
     * Returns null when the URL is not a directions link.
     */
    fun parseDirections(url: String): List<Stop>? = parseDirectionsFull(url)?.stops

    /**
     * Supports `/maps/dir/A/B/C/data=...` and `?api=1&origin=...&destination=...&waypoints=a|b`.
     * Returns null when the URL is not a directions link.
     */
    fun parseDirectionsFull(url: String): Directions? {
        dirPath(url)?.takeIf { it.stops.isNotEmpty() }?.let { return it }
        if (!url.contains("destination=")) return null
        val query = url.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to (decode(v) ?: v)
        }.toMap()
        val destination = params["destination"] ?: return null
        val waypoints = params["waypoints"]?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        val origin = params["origin"]?.takeIf { it.isNotBlank() }?.let(::stopOf)
        return Directions(origin, (waypoints + destination).map(::stopOf))
    }

    /** Short display name: first comma-separated segment of the place text. */
    fun displayName(placeText: String): String = placeText.substringBefore(',').trim()

    /**
     * Address-only variant (drops the business name) for geocoders that don't know businesses.
     * Returns null when there is nothing left to drop.
     */
    fun addressOnly(placeText: String): String? =
        placeText.substringAfter(',', "").trim().takeIf { it.contains(',') || it.any(Char::isDigit) }

    // ---------------------------------------------------------------- directions

    private fun dirPath(url: String): Directions? {
        val afterDir = url.substringAfter("/maps/dir/", "").takeIf { url.contains("/maps/dir/") } ?: return null
        val path = afterDir.split("/data=", "/@", "?", "#").first()
        val segments = path.split('/').map { decode(it)?.trim().orEmpty() }
        // First segment is the origin (empty = "your location").
        val origin = segments.firstOrNull()?.takeIf { it.isNotEmpty() }?.let(::stopOf)
        val stops = segments.drop(1).filter { it.isNotEmpty() }.map(::stopOf)

        // The data blob holds `!1d<lng>!2d<lat>` for place stops, in order. Only trust it
        // when the count matches the stops that still need coordinates (origin included or not).
        val blob = Regex("""!1d$NUM!2d$NUM""").findAll(url).map { LatLng(it.groupValues[2].toDouble(), it.groupValues[1].toDouble()) }.toList()
        val needCoords = stops.count { it.coords == null }
        val originNeeds = origin != null && origin.coords == null
        return when {
            blob.size == needCoords + 1 && originNeeds ->
                Directions(origin!!.copy(coords = blob.first()), fill(stops, blob.drop(1)))
            blob.size == needCoords -> Directions(origin, fill(stops, blob))
            else -> Directions(origin, stops)
        }
    }

    private fun fill(stops: List<Stop>, coords: List<LatLng>): List<Stop> {
        val next = coords.iterator()
        return stops.map { s -> if (s.coords == null && next.hasNext()) s.copy(coords = next.next()) else s }
    }

    private fun stopOf(text: String): Stop {
        val coords = pair(Regex("""^\s*$NUM$SEP$NUM\s*$"""))(text)
        return Stop(name = if (coords == null) text else null, coords = coords)
    }

    // ---------------------------------------------------------------- coordinate finders

    private fun valid(lat: Double?, lng: Double?): LatLng? =
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) {
            LatLng(lat, lng)
        } else null

    private fun pair(regex: Regex): (String) -> LatLng? = { text ->
        regex.findAll(text).firstNotNullOfOrNull { valid(it.groupValues[1].toDoubleOrNull(), it.groupValues[2].toDoubleOrNull()) }
    }

    private fun separate(latKey: String, lngKey: String): (String) -> LatLng? = { text ->
        val lat = Regex("""[?&]$latKey=$NUM(?:&|$|#)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val lng = Regex("""[?&]$lngKey=$NUM(?:&|$|#)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        valid(lat, lng)
    }

    private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

    // ---------------------------------------------------------------- ge0 (Organic Maps / MAPS.ME)
    // Port of organicmaps/libs/ge0/parser.cpp (Apache-2.0): 1 char zoom + 9 chars of
    // interleaved lat/lon bits in URL-safe base64, then an optional "/Name".

    private const val GE0_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val GE0_MAX_POINT_BYTES = 10
    private const val GE0_MAX_COORD_BITS = GE0_MAX_POINT_BYTES * 3
    private val ge0Regex = Regex("""(?:https?://(?:omaps\.app|ge0\.me|maps\.me/link/ge0)/|om://|ge0://)([A-Za-z0-9_-]{10})(?:/([^\s?#]*))?""")

    private fun ge0(text: String): LatLng? {
        val code = ge0Regex.find(text)?.groupValues?.get(1) ?: return null
        var lat = 0
        var lon = 0
        var shift = GE0_MAX_COORD_BITS - 3
        for (c in code.substring(1)) {
            val a = GE0_ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            val lat1 = ((a shr 5) and 1) shl 2 or (((a shr 3) and 1) shl 1) or ((a shr 1) and 1)
            val lon1 = ((a shr 4) and 1) shl 2 or (((a shr 2) and 1) shl 1) or (a and 1)
            lat = lat or (lat1 shl shift)
            lon = lon or (lon1 shl shift)
            shift -= 3
        }
        val remainingBits = 3 * (GE0_MAX_POINT_BYTES - (code.length - 1)) - 1
        if (remainingBits >= 0) {
            lat += 1 shl remainingBits
            lon += 1 shl remainingBits
        }
        val max = (1 shl GE0_MAX_COORD_BITS) - 1
        val latD = lat.toDouble() / max * 180 - 90
        val lonD = lon.toDouble() / (max + 1.0) * 360 - 180
        return if (latD in -90.0..90.0 && lonD in -180.0..180.0) LatLng(latD, lonD) else null
    }

    /** ge0 names use '_' for spaces (and '%20' for a literal '_'). */
    private fun decodeGe0Name(raw: String): String? {
        val cut = Regex("""%(?![0-9A-Fa-f]{2})""").find(raw)?.range?.first?.let { raw.substring(0, it) } ?: raw
        val decoded = runCatching { URLDecoder.decode(cut.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return decoded.map { if (it == '_') ' ' else if (it == ' ') '_' else it }.joinToString("").trim().ifEmpty { null }
    }
}
