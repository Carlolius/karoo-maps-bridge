package com.carlolius.karoomapsbridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BRouterTest {

    // Same shape as a real BRouter GeoJSON answer (format=geojson), trimmed down.
    private val geojson = """
    {"type":"FeatureCollection","features":[{"type":"Feature",
     "properties":{"creator":"BRouter-1.7.10","name":"brouter_trekking_0","track-length":"1000",
       "filtered ascend":"12","plain-ascend":"8","total-time":"215",
       "messages":[
        ["Longitude","Latitude","Elevation","Distance","CostPerKm","ElevCost","TurnCost","NodeCost","InitialCost","WayTags","NodeTags","Time","Energy"],
        ["2170110","41387100","45","400","1450","0","0","0","0","highway=tertiary surface=asphalt oneway=yes","","1","1"],
        ["2170200","41388000","50","200","1450","0","0","0","0","highway=cycleway surface=paving_stones","","2","2"],
        ["2170200","41388000","50","150","1450","0","0","0","0","highway=residential","","3","3"],
        ["2170200","41388000","50","150","1450","0","0","0","0","highway=track surface=gravel","","4","4"],
        ["2170200","41388000","50","100","1450","0","0","0","0","highway=path","","5","5"]
       ]},
     "geometry":{"type":"LineString","coordinates":[[2.170087,41.387019,44.5],[2.17011,41.3871,45.0],[2.1702,41.388,50.2]]}}]}
    """

    private val route = BRouter.parse(geojson, BRouter.Profile.NORMAL, 0)!!

    @Test fun summary() {
        assertEquals(BRouter.Info(lengthM = 1000, ascendM = 12, timeS = 215), route.info)
        assertEquals(3, route.points.size)
        assertEquals(41.387019, route.points[0].lat, 1e-9)
        assertEquals(2.170087, route.points[0].lng, 1e-9)
        assertEquals(44.5, route.points[0].ele!!, 1e-9)
    }

    @Test fun surfaceBreakdown() {
        val s = route.surface
        assertEquals(400 + 150, s.asphaltM) // asphalt tag + residential without surface tag
        assertEquals(200, s.cobblesM)
        assertEquals(150, s.unpavedM)
        assertEquals(100, s.unknownM) // path without surface: not assumed
        assertEquals(200, s.cyclewayM)
        assertEquals(1000, s.totalM)
    }

    @Test fun noRoute() {
        assertNull(BRouter.parse("""{"type":"FeatureCollection","features":[]}""", BRouter.Profile.FAST, 3))
    }

    @Test fun gpxForTheKaroo() {
        val gpx = BRouter.toGpx(route, "Parc Güell (Normal · option 2) <&>")
        assertTrue(gpx.contains("<name>Parc Güell (Normal · option 2) &lt;&amp;&gt;</name>"))
        assertTrue(gpx.contains("""<trkpt lat="41.387019" lon="2.170087"><ele>44.5</ele></trkpt>"""))
        assertEquals(3, Regex("<trkpt").findAll(gpx).count())
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test fun urlUsesLonLatOrder() {
        val url = BRouter.url(
            listOf(MapsLinkParser.LatLng(41.3870194, 2.1700867), MapsLinkParser.LatLng(41.4144948, 2.1527436)),
            BRouter.Profile.QUIET, 2,
        )
        assertEquals("https://brouter.de/brouter?lonlats=2.170087,41.387019|2.152744,41.414495&profile=safety&alternativeidx=2&format=geojson", url)
    }

    @Test fun thinKeepsEnds() {
        val many = (0 until 1000).map { BRouter.TrackPoint(41.0 + it / 1000.0, 2.0, null) }
        val pts = BRouter.thin(many, max = 100)
        assertTrue(pts.size in 100..101)
        assertEquals(41.0, pts.first().lat, 1e-9)
        assertEquals(41.999, pts.last().lat, 1e-9)
    }

    // ---- near-duplicates: lines along a meridian, 1 km long, points every 10 m

    private fun line(lngAt: (Double) -> Double) =
        (0..100).map { i -> val lat = 41.4 + i * 0.00009; BRouter.TrackPoint(lat, lngAt(i / 100.0), null) }

    private fun routeOf(points: List<BRouter.TrackPoint>, length: Int) =
        BRouter.Route(BRouter.Profile.NORMAL, 0, BRouter.Info(length, 0, length / 4), BRouter.Surface(0, 0, 0, 0, 0), points)

    @Test fun sameStreetIsDuplicate() {
        val a = routeOf(line { 2.2 }, 1000)
        val b = routeOf(line { 2.2 + 0.0001 }, 1010) // ~8 m to the side: same street
        assertTrue(BRouter.nearDuplicate(a, b))
    }

    @Test fun parallelStreetIsDifferent() {
        val a = routeOf(line { 2.2 }, 1000)
        val b = routeOf(line { 2.2 + 0.002 }, 1000) // ~170 m away
        assertFalse(BRouter.nearDuplicate(a, b))
    }

    @Test fun shortDetourIsDuplicateLongOneIsNot() {
        val a = routeOf(line { 2.2 }, 1000)
        // Leaves the street for 10 % of the way: still "almost the same"
        val small = routeOf(line { f -> if (f in 0.45..0.55) 2.2 + 0.002 else 2.2 }, 1100)
        assertTrue(BRouter.nearDuplicate(a, small))
        // Leaves it for 40 %: a real alternative
        val big = routeOf(line { f -> if (f in 0.3..0.7) 2.2 + 0.002 else 2.2 }, 1400)
        assertFalse(BRouter.nearDuplicate(a, big))
    }
}
