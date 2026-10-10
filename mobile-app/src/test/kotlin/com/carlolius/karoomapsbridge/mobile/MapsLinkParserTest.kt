package com.carlolius.karoomapsbridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Links have the exact shape of real shares from Google Maps and MAPS.ME (Sep 2026, resolved
 * from maps.app.goo.gl), with the places swapped for public landmarks in Barcelona.
 */
class MapsLinkParserTest {

    private val business = "https://www.google.com/maps/place/Mercat+de+la+Boqueria,+La+Rambla,+91,+Ciutat+Vella,+08001+Barcelona/data=!4m2!3m1!1s0x12a4a2f6c5d1e2a1:0x1d2c3b4a59687706!18m1!1e1?utm_source=mstt_1&entry=gps"
    private val neighbourhood = "https://www.google.com/maps/place/Vila+de+Gr%C3%A0cia,+08012,+Barcelona/data=!4m2!3m1!1s0x12a4a2bd7e2c1b03:0x5a6b7c8d9e0f1a2b!18m1!1e1?utm_source=mstt_1&entry=gps"
    private val droppedPin = "https://www.google.com/maps/place/Passeig+de+Llu%C3%ADs+Companys,+1,+08018+Barcelona/data=!4m6!3m5!1s0x12a4a2fd5e6f7a81:0x9b8a7c6d5e4f3a21!7e2!8m2!3d41.3910524!4d2.1806449!18m1!1e1?utm_source=mstt_1&entry=gps"

    @Test fun droppedPinHasExactCoords() {
        val c = MapsLinkParser.parseCoords(droppedPin)!!
        assertEquals(41.3910524, c.lat, 1e-9)
        assertEquals(2.1806449, c.lng, 1e-9)
    }

    @Test fun businessHasNoCoordsButHasPlaceText() {
        assertNull(MapsLinkParser.parseCoords(business))
        val place = MapsLinkParser.placeText(business)!!
        assertEquals("Mercat de la Boqueria, La Rambla, 91, Ciutat Vella, 08001 Barcelona", place)
        assertEquals("Mercat de la Boqueria", MapsLinkParser.displayName(place))
        assertEquals("La Rambla, 91, Ciutat Vella, 08001 Barcelona", MapsLinkParser.addressOnly(place))
    }

    @Test fun neighbourhoodPlaceText() {
        assertNull(MapsLinkParser.parseCoords(neighbourhood))
        assertEquals("Vila de Gràcia, 08012, Barcelona", MapsLinkParser.placeText(neighbourhood))
    }

    @Test fun extractsUrlFromSharedText() {
        val shared = "Mercat de la Boqueria\nLa Rambla, 91\nhttps://maps.app.goo.gl/AbCdEfGhIjKlMnOp9"
        val url = MapsLinkParser.extractUrl(shared)
        assertEquals("https://maps.app.goo.gl/AbCdEfGhIjKlMnOp9", url)
        assertTrue(MapsLinkParser.isShortLink(url!!))
    }

    @Test fun otherCoordinateFormats() {
        assertNotNull(MapsLinkParser.parseCoords("geo:41.43,2.21?z=17"))
        assertEquals(41.5, MapsLinkParser.parseCoords("geo:0,0?q=41.5,2.1(Casa)")!!.lat, 1e-9)
        assertEquals(2.1, MapsLinkParser.parseCoords("https://maps.google.com/?q=41.5%2C2.1")!!.lng, 1e-9)
        assertEquals(-3.7, MapsLinkParser.parseCoords("40.41, -3.7")!!.lng, 1e-9)
        assertNull(MapsLinkParser.parseCoords("geo:0,0?q=Carrer+de+Mallorca+401"))
    }

    private fun assertCoords(text: String, lat: Double, lng: Double, tol: Double = 1e-6) {
        val c = MapsLinkParser.parseCoords(text)
        assertNotNull("no coords in $text", c)
        assertEquals("lat of $text", lat, c!!.lat, tol)
        assertEquals("lng of $text", lng, c.lng, tol)
    }

    // Vectors from organicmaps/libs/ge0/ge0_tests/parser_tests.cpp
    @Test fun organicMapsGe0Links() {
        assertCoords("ge0://B4srhdHVVt/Name", 64.5234, 12.1234, 1e-4)
        assertCoords("ge0://BaF6F6F6F6/Name", -20.0, 20.0, 1e-4)
        assertCoords("Mira esto https://omaps.app/Bzqqqqqqqq/Sitio", 45.0, 45.0, 1e-4)
        assertCoords("om://Byqqqqqqqq/x y", 45.0, 0.00017, 1e-3)
        assertEquals("Super Poi", MapsLinkParser.ge0Name("ge0://AwAAAAAAAA/Super_Poi"))
        assertEquals("Super_Poi", MapsLinkParser.ge0Name("ge0://AwAAAAAAAA/Super%20Poi"))
        assertEquals("Hello", MapsLinkParser.ge0Name("ge0://AwAAAAAAAA/Hello%%%%World"))
        assertEquals("https://omaps.app/Bzqqqqqqqq/Sitio", MapsLinkParser.extractUrl("Mira esto https://omaps.app/Bzqqqqqqqq/Sitio"))
    }

    /** Same text layout as a real MAPS.ME share; ge0 code encoding Plaça Reial. */
    @Test fun mapsMeShare() {
        val shared = "📍 Plaza \"Plaça Reial\"\nBarcelona, Provincia de Barcelona, España\n" +
            "Abrir en MAPS.ME:\nhttps://maps.me/link/ge0/0yoni2AAcc/Plaça_Reial"
        val c = MapsLinkParser.parseCoords(shared)!!
        assertEquals(41.38003, c.lat, 1e-4)
        assertEquals(2.17544, c.lng, 1e-4)
        assertEquals("Plaça Reial", MapsLinkParser.ge0Name(shared))
    }

    @Test fun openStreetMapAndOsmAnd() {
        assertCoords("https://www.openstreetmap.org/?mlat=41.4036&mlon=2.1744#map=17/41.4000/2.1700", 41.4036, 2.1744)
        assertCoords("https://www.openstreetmap.org/#map=17/41.40/2.17", 41.40, 2.17)
        assertCoords("https://osmand.net/map?pin=41.40,2.17#17/41.39/2.16", 41.40, 2.17)
        assertCoords("https://osmand.net/go?lat=41.40&lon=2.17&z=16", 41.40, 2.17)
    }

    @Test fun appleAndWaze() {
        assertCoords("https://maps.apple.com/?ll=41.40,2.17&q=Casa", 41.40, 2.17)
        assertCoords("https://maps.apple.com/place?coordinate=41.40,2.17&name=X", 41.40, 2.17)
        assertCoords("https://waze.com/ul?ll=41.40%2C2.17&navigate=yes", 41.40, 2.17)
    }

    @Test fun directionsPathWithBlobCoords() {
        // Origin empty (your location), a named place (coords in the blob) and a coordinate stop.
        val url = "https://www.google.com/maps/dir//Mercat+de+la+Boqueria,+La+Rambla,+91,+Barcelona/41.39,2.18/" +
            "data=!4m9!4m8!1m0!1m5!1m1!1s0x12a4a2f6c5d1e2a1:0x1d2c3b4a59687706!2m2!1d2.1716!2d41.3817!1m0!3e1"
        val stops = MapsLinkParser.parseDirections(url)!!
        assertEquals(2, stops.size)
        assertEquals("Mercat de la Boqueria, La Rambla, 91, Barcelona", stops[0].name)
        assertEquals(41.3817, stops[0].coords!!.lat, 1e-9)
        assertEquals(2.1716, stops[0].coords!!.lng, 1e-9)
        assertNull(stops[1].name)
        assertEquals(41.39, stops[1].coords!!.lat, 1e-9)
    }

    // Exact shape of real bike directions shared from Google Maps: named origin and
    // destination, both with coordinates in the data blob, travel mode !3e1 (bicycle).
    private val bikeDirections = "https://www.google.com/maps/dir/Pla%C3%A7a+de+Catalunya,+08002+Barcelona,+Espa%C3%B1a/" +
        "Parc+G%C3%BCell,+Gr%C3%A0cia,+08024+Barcelona,+Espa%C3%B1a/@41.4007,2.1614,14z/am=t/" +
        "data=!4m14!4m13!1m5!1m1!1s0x12a4a2f2f0c1d3e5:0x6b5a4c3d2e1f0a98!2m2!1d2.1700867!2d41.3870194" +
        "!1m5!1m1!1s0x12a4a2ae52d441ab:0x899a0ba01aaace58!2m2!1d2.1527436!2d41.4144948!3e1" +
        "?entry=tts&g_ep=EgoyMDI2MDkyMy4wIPu8ASoASAFQAw%3D%3D&skid=00000000-0000-0000-0000-000000000000"

    @Test fun sharedBikeDirections() {
        val stops = MapsLinkParser.parseDirections(bikeDirections)!!
        assertEquals(1, stops.size) // origin skipped
        assertEquals("Parc Güell, Gràcia, 08024 Barcelona, España", stops[0].name)
        assertEquals(41.4144948, stops[0].coords!!.lat, 1e-9)
        assertEquals(2.1527436, stops[0].coords!!.lng, 1e-9)
    }

    @Test fun sharedBikeDirectionsOrigin() {
        val origin = MapsLinkParser.parseDirectionsFull(bikeDirections)!!.origin!!
        assertTrue(origin.name!!.startsWith("Plaça de Catalunya"))
        assertEquals(41.3870194, origin.coords!!.lat, 1e-9)
        assertEquals(2.1700867, origin.coords!!.lng, 1e-9)
    }

    @Test fun yourLocationOriginIsNull() {
        val url = "https://www.google.com/maps/dir//Mercat+de+la+Boqueria,+Barcelona/data=!4m9!4m8!1m0!1m5!1m1!1s0x1:0x2!2m2!1d2.1716!2d41.3817!3e1"
        val d = MapsLinkParser.parseDirectionsFull(url)!!
        assertNull(d.origin)
        assertEquals(41.3817, d.stops.single().coords!!.lat, 1e-9)
    }

    @Test fun apiStyleOrigin() {
        val d = MapsLinkParser.parseDirectionsFull("https://www.google.com/maps/dir/?api=1&origin=41.4,2.2&destination=Montgat")!!
        assertEquals(41.4, d.origin!!.coords!!.lat, 1e-9)
        assertEquals("Montgat", d.stops.single().name)
    }

    @Test fun directionsNamedOriginIsSkipped() {
        val url = "https://www.google.com/maps/dir/Casa,+Barcelona/Casa+Batll%C3%B3,+Passeig+de+Gr%C3%A0cia,+43/" +
            "@41.39,2.17,14z/data=!4m14!4m13!1m5!1m1!1s0x1:0x2!2m2!1d2.17!2d41.38!1m5!1m1!1s0x3:0x4!2m2!1d2.1649!2d41.3916!3e1"
        val stops = MapsLinkParser.parseDirections(url)!!
        assertEquals(1, stops.size)
        assertTrue(stops[0].name!!.startsWith("Casa Batlló"))
        assertEquals(41.3916, stops[0].coords!!.lat, 1e-9)
    }

    @Test fun directionsWithoutBlobNeedGeocoding() {
        val stops = MapsLinkParser.parseDirections("https://www.google.com/maps/dir/Barcelona/Montgat/Masnou")!!
        assertEquals(listOf("Montgat", "Masnou"), stops.map { it.name })
        assertTrue(stops.all { it.coords == null })
    }

    @Test fun directionsApiStyle() {
        val url = "https://www.google.com/maps/dir/?api=1&origin=Casa&destination=41.5,2.1&waypoints=Montgat%7CMasnou&travelmode=bicycling"
        val stops = MapsLinkParser.parseDirections(url)!!
        assertEquals(listOf("Montgat", "Masnou", null), stops.map { it.name })
        assertEquals(41.5, stops[2].coords!!.lat, 1e-9)
    }

    @Test fun placeLinkIsNotDirections() {
        assertNull(MapsLinkParser.parseDirections(business))
        assertNull(MapsLinkParser.parseDirections(droppedPin))
    }
}
