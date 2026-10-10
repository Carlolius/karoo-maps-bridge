package com.carlolius.karoomapsbridge.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Bike routing with BRouter (OpenStreetMap data, free public server, no key).
 * Google Maps never shares a route's geometry, so a shared directions link gives us
 * only its stops; BRouter routes through them and offers alternatives to choose from.
 *
 * Asks for GeoJSON (geometry + per-segment OSM tags, so we can tell the surface) and
 * writes the GPX for the Karoo ourselves: one request per alternative.
 */
object BRouter {

    /** BRouter profile id and how the UI names it. */
    enum class Profile(val id: String) {
        NORMAL("trekking"),
        FAST("fastbike"),
        QUIET("safety"), // avoids traffic
    }

    const val MAX_ALTERNATIVES = 4 // alternativeidx 0..3

    /** Point with elevation (metres, may be null). */
    data class TrackPoint(val lat: Double, val lng: Double, val ele: Double?)

    data class Info(val lengthM: Int, val ascendM: Int, val timeS: Int)

    /** Metres of the route on each kind of surface, plus metres on dedicated cycleways. */
    data class Surface(val asphaltM: Int, val cobblesM: Int, val unpavedM: Int, val unknownM: Int, val cyclewayM: Int) {
        val totalM get() = asphaltM + cobblesM + unpavedM + unknownM
    }

    data class Route(
        val profile: Profile,
        val alternative: Int,
        val info: Info,
        val surface: Surface,
        val points: List<TrackPoint>,
    )

    private const val SERVER = "https://brouter.de/brouter"
    private const val USER_AGENT = "karoo-maps-bridge/1.0 (+https://github.com/Carlolius/karoo-maps-bridge)"

    fun url(points: List<MapsLinkParser.LatLng>, profile: Profile, alternative: Int): String {
        val lonlats = points.joinToString("|") { String.format(Locale.ROOT, "%.6f,%.6f", it.lng, it.lat) }
        return "$SERVER?lonlats=$lonlats&profile=${profile.id}&alternativeidx=$alternative&format=geojson"
    }

    /** Parses BRouter's GeoJSON answer. */
    fun parse(json: String, profile: Profile, alternative: Int): Route? {
        val feature = JSONObject(json).optJSONArray("features")?.optJSONObject(0) ?: return null
        val props = feature.getJSONObject("properties")
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            TrackPoint(c.getDouble(1), c.getDouble(0), if (c.length() > 2) c.getDouble(2) else null)
        }
        if (points.size < 2) return null
        val info = Info(
            lengthM = props.optString("track-length").toIntOrNull() ?: return null,
            ascendM = props.optString("filtered ascend").toIntOrNull() ?: 0,
            timeS = props.optString("total-time").toIntOrNull() ?: 0,
        )
        return Route(profile, alternative, info, surface(props.optJSONArray("messages")), points)
    }

    /**
     * Sums segment lengths by surface. `messages` is a table: first row = column names
     * (Distance, WayTags…), then one row per segment.
     */
    private fun surface(messages: org.json.JSONArray?): Surface {
        if (messages == null || messages.length() < 2) return Surface(0, 0, 0, 0, 0)
        val header = messages.getJSONArray(0)
        val cols = (0 until header.length()).associateBy { header.getString(it) }
        val distCol = cols["Distance"] ?: return Surface(0, 0, 0, 0, 0)
        val tagsCol = cols["WayTags"] ?: return Surface(0, 0, 0, 0, 0)
        var asphalt = 0; var cobbles = 0; var unpaved = 0; var unknown = 0; var cycleway = 0
        for (i in 1 until messages.length()) {
            val row = messages.getJSONArray(i)
            val d = row.optString(distCol).toIntOrNull() ?: continue
            val tags = row.optString(tagsCol).split(' ').mapNotNull { t -> t.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0] to it[1] }
            if (tags["highway"] == "cycleway") cycleway += d
            when (surfaceKind(tags["surface"], tags["highway"])) {
                SurfaceKind.ASPHALT -> asphalt += d
                SurfaceKind.COBBLES -> cobbles += d
                SurfaceKind.UNPAVED -> unpaved += d
                SurfaceKind.UNKNOWN -> unknown += d
            }
        }
        return Surface(asphalt, cobbles, unpaved, unknown, cycleway)
    }

    private enum class SurfaceKind { ASPHALT, COBBLES, UNPAVED, UNKNOWN }

    private val paved = setOf("asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes", "chipseal", "metal", "wood")
    private val cobbles = setOf("paving_stones", "sett", "cobblestone", "unhewn_cobblestone", "bricks", "grass_paver")
    private val unpaved = setOf(
        "unpaved", "gravel", "fine_gravel", "compacted", "dirt", "earth", "ground", "grass", "sand",
        "mud", "pebblestone", "rock", "woodchips", "shells",
    )
    // Without a surface tag these are paved in practice (streets and roads); tracks/paths are not assumed.
    private val usuallyPaved = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
        "living_street", "service", "cycleway", "pedestrian",
    ).flatMap { listOf(it, "${it}_link") }.toSet()

    private fun surfaceKind(surface: String?, highway: String?): SurfaceKind = when {
        surface in paved -> SurfaceKind.ASPHALT
        surface in cobbles -> SurfaceKind.COBBLES
        surface in unpaved -> SurfaceKind.UNPAVED
        surface == null && highway in usuallyPaved -> SurfaceKind.ASPHALT
        else -> SurfaceKind.UNKNOWN
    }

    /** GPX 1.1 track for the Karoo / map apps. */
    fun toGpx(route: Route, name: String): String {
        val escaped = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="karoo-maps-bridge (BRouter, OpenStreetMap)">""").append('\n')
        sb.append(" <trk>\n  <name>").append(escaped).append("</name>\n  <trkseg>\n")
        for (p in route.points) {
            sb.append(String.format(Locale.ROOT, "   <trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lng))
            if (p.ele != null) sb.append(String.format(Locale.ROOT, "<ele>%.1f</ele>", p.ele))
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg>\n </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Points thinned to at most [max] (enough to draw it on a map or compare routes). */
    fun thin(points: List<TrackPoint>, max: Int = 400): List<TrackPoint> {
        if (points.size <= max) return points
        val step = (points.size + max - 1) / max
        return points.filterIndexed { i, _ -> i % step == 0 } + points.last()
    }

    /**
     * Fraction (0..1) of [a]'s points that lie within [toleranceM] of the line [b].
     * Equirectangular projection: fine at city/region scale.
     */
    fun overlap(a: List<TrackPoint>, b: List<TrackPoint>, toleranceM: Double = 30.0): Double {
        if (a.isEmpty() || b.size < 2) return 0.0
        val lat0 = Math.toRadians(a.first().lat)
        val kx = 111_320.0 * cos(lat0)
        val ky = 110_540.0
        fun x(p: TrackPoint) = p.lng * kx
        fun y(p: TrackPoint) = p.lat * ky
        val sample = thin(a, 200)
        val near = sample.count { p ->
            val px = x(p); val py = y(p)
            (0 until b.size - 1).any { i ->
                val ax = x(b[i]); val ay = y(b[i]); val bx = x(b[i + 1]); val by = y(b[i + 1])
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val ex = ax + t * dx - px; val ey = ay + t * dy - py
                sqrt(ex * ex + ey * ey) <= toleranceM
            }
        }
        return near.toDouble() / sample.size
    }

    /**
     * Two routes that share most of their way (both directions) are not worth showing twice.
     * 0.7 measured on a real 10 km city route: 12 BRouter alternatives -> 9 (0.85 -> 11, 0.65 -> 7);
     * most pairs share under 40 %, so lower values start merging genuinely different routes.
     */
    fun nearDuplicate(a: Route, b: Route, threshold: Double = 0.7): Boolean =
        overlap(a.points, b.points) >= threshold && overlap(b.points, a.points) >= threshold

    /** Blocking network call. Returns null when BRouter has no route for that index. */
    fun fetch(points: List<MapsLinkParser.LatLng>, profile: Profile, alternative: Int): Route? {
        val conn = (URL(url(points, profile, alternative)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) return null
            return parse(conn.inputStream.bufferedReader().readText(), profile, alternative)
        } finally {
            conn.disconnect()
        }
    }
}
