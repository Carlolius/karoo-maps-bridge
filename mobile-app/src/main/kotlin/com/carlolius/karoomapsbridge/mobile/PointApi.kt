package com.carlolius.karoomapsbridge.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * The HTTP API the Karoo talks to, as a pure function (no sockets, no Android) so it can be tested.
 *
 *  - `GET /points` -> `{"points": [ {id, lat, lng, name, source, timestamp}, ... ]}` newest first
 *  - `GET /point`  -> newest point or `{"hasPoint": false}` (v0.2 Karoo extension)
 *  - `GET /`       -> `{"app": "karoo-maps-bridge", "ok": true}`
 */
object PointApi {

    /** [delivered]: points were handed to the Karoo (the server may wind down). */
    data class Response(val status: String, val body: String, val delivered: Boolean = false)

    fun respond(method: String, path: String, points: List<PendingPoint>): Response {
        if (method != "GET") return Response("405 Method Not Allowed", """{"error":"method"}""")
        return when (path.substringBefore('?')) {
            "/points" -> Response(
                "200 OK",
                JSONObject().put("points", JSONArray().apply { points.forEach { put(it.toJson()) } }).toString(),
                delivered = points.isNotEmpty(),
            )
            "/point" -> Response(
                "200 OK",
                (points.firstOrNull()?.toJson() ?: JSONObject().put("hasPoint", false)).toString(),
                delivered = points.isNotEmpty(),
            )
            "/" -> Response("200 OK", """{"app":"karoo-maps-bridge","ok":true}""")
            else -> Response("404 Not Found", """{"error":"not found"}""")
        }
    }
}
