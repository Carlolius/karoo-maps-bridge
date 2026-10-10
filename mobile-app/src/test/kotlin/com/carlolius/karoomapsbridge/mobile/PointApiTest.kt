package com.carlolius.karoomapsbridge.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointApiTest {

    private val a = PendingPoint("id-a", 41.3817, 2.1716, "1/2 · Mercat de la Boqueria", "geocoded", 2000)
    private val b = PendingPoint("id-b", 41.3916, 2.1649, null, "exact", 1000)

    @Test fun pointsListNewestFirst() {
        val r = PointApi.respond("GET", "/points", listOf(a, b))
        assertEquals("200 OK", r.status)
        assertTrue(r.delivered)
        val list = JSONObject(r.body).getJSONArray("points")
        assertEquals(2, list.length())
        assertEquals("id-a", list.getJSONObject(0).getString("id"))
        assertEquals("1/2 · Mercat de la Boqueria", list.getJSONObject(0).getString("name"))
        assertTrue(list.getJSONObject(1).isNull("name"))
        assertEquals(2.1649, list.getJSONObject(1).getDouble("lng"), 1e-9)
    }

    @Test fun emptyListIsNotDelivered() {
        val r = PointApi.respond("GET", "/points?x=1", emptyList())
        assertEquals(0, JSONObject(r.body).getJSONArray("points").length())
        assertFalse(r.delivered)
    }

    @Test fun legacySinglePointEndpoint() {
        assertEquals("id-a", JSONObject(PointApi.respond("GET", "/point", listOf(a, b)).body).getString("id"))
        val none = PointApi.respond("GET", "/point", emptyList())
        assertFalse(JSONObject(none.body).getBoolean("hasPoint"))
        assertFalse(none.delivered)
    }

    @Test fun otherRequests() {
        assertEquals("404 Not Found", PointApi.respond("GET", "/nope", listOf(a)).status)
        assertEquals("405 Method Not Allowed", PointApi.respond("POST", "/points", listOf(a)).status)
        assertTrue(JSONObject(PointApi.respond("GET", "/", emptyList()).body).getBoolean("ok"))
    }
}
