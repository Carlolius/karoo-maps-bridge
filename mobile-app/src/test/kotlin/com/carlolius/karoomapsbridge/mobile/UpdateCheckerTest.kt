package com.carlolius.karoomapsbridge.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun newerVersions() {
        assertTrue(UpdateChecker.isNewer("v1.1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.99"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
    }

    @Test fun sameOrOlder() {
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "1.0.0"))
    }
}
