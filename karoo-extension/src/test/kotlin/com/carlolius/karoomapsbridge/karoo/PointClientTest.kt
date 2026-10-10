package com.carlolius.karoomapsbridge.karoo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Runs PointClient against a real local HTTP server that mimics the phone app. */
class PointClientTest {

    private var server: ServerSocket? = null

    /** Starts a server answering [routes] (path -> JSON body); anything else is 404. */
    private fun phone(routes: Map<String, String>): String {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                client.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1)?.substringBefore('?') ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) { /* headers */ }
                    val body = routes[path]
                    val bytes = (body ?: """{"error":"not found"}""").toByteArray()
                    val status = if (body != null) "200 OK" else "404 Not Found"
                    c.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        return "127.0.0.1:${s.localPort}"
    }

    @After fun stop() {
        server?.close()
    }

    private val twoPoints = """{"points":[
        {"hasPoint":true,"id":"a","lat":41.3817,"lng":2.1716,"name":"1/2 · Mercat de la Boqueria","source":"geocoded","timestamp":2},
        {"hasPoint":true,"id":"b","lat":41.3916,"lng":2.1649,"name":null,"source":"exact","timestamp":1}]}"""

    @Test fun pointsList() {
        val r = PointClient.fetchFrom(phone(mapOf("/points" to twoPoints)))
        r as PointClient.Result.Found
        assertEquals(listOf("a", "b"), r.points.map { it.id })
        assertEquals("1/2 · Mercat de la Boqueria", r.points[0].name)
        assertNull(r.points[1].name)
        assertEquals(2.1649, r.points[1].lng, 1e-9)
    }

    @Test fun emptyList() {
        assertTrue(PointClient.fetchFrom(phone(mapOf("/points" to """{"points":[]}"""))) is PointClient.Result.NoPoint)
    }

    @Test fun oldPhoneAppFallsBackToSinglePoint() {
        val host = phone(mapOf("/point" to """{"hasPoint":true,"id":"x","lat":41.4,"lng":2.2,"name":"Old","source":"exact","timestamp":1}"""))
        val r = PointClient.fetchFrom(host) as PointClient.Result.Found
        assertEquals("Old", r.points.single().name)
    }

    @Test fun oldPhoneAppWithoutPoint() {
        assertTrue(PointClient.fetchFrom(phone(mapOf("/point" to """{"hasPoint":false}"""))) is PointClient.Result.NoPoint)
    }

    @Test fun somethingElseOnThatPortIsRejected() {
        val host = phone(mapOf("/points" to """{"hello":"world"}"""))
        try {
            PointClient.fetchFrom(host)
            fail("should not accept a server that is not the phone app")
        } catch (e: IllegalStateException) {
            // expected: fetch() then tries the next candidate
        }
    }
}
