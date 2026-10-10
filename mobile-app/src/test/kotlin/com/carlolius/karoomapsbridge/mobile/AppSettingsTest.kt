package com.carlolius.karoomapsbridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    @Test fun customServerIsCleaned() {
        assertEquals("https://brouter.example.org/brouter", AppSettings.normalizeServer("  https://brouter.example.org/brouter/ "))
        assertEquals("http://192.168.1.10:17777/brouter", AppSettings.normalizeServer("http://192.168.1.10:17777/brouter"))
    }

    @Test fun blankDefaultOrInvalidMeanDefault() {
        assertNull(AppSettings.normalizeServer(null))
        assertNull(AppSettings.normalizeServer("   "))
        assertNull(AppSettings.normalizeServer("https://brouter.de/brouter/"))
        assertNull(AppSettings.normalizeServer("brouter.example.org/brouter"))
    }

    @Test fun routesUseTheConfiguredServer() {
        val url = BRouter.url(
            listOf(MapsLinkParser.LatLng(41.38, 2.17), MapsLinkParser.LatLng(41.41, 2.15)),
            BRouter.Profile.NORMAL, 0, "http://192.168.1.10:17777/brouter",
        )
        assertEquals("http://192.168.1.10:17777/brouter?lonlats=2.170000,41.380000|2.150000,41.410000&profile=trekking&alternativeidx=0&format=geojson", url)
    }
}
