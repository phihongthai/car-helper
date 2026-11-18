package com.carhelper.signs.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitTrackerTest {

    private fun tracker() = SpeedLimitTracker(toleranceKmh = 3, sustainMs = 3_000, repeatMs = 20_000, expireMeters = 2_000.0)

    @Test
    fun noLimitNoAlert() {
        val t = tracker()
        assertFalse(t.onSpeed(120f, 0, 0.0))
        assertFalse(t.onSpeed(120f, 10_000, 100.0))
    }

    @Test
    fun alertsOnlyAfterSustainedOverspeed() {
        val t = tracker()
        t.onLimitSign(60)
        assertFalse(t.onSpeed(63f, 0, 0.0)) // trong dung sai
        assertFalse(t.onSpeed(70f, 1_000, 10.0)) // mới bắt đầu vượt
        assertFalse(t.onSpeed(70f, 3_000, 30.0))
        assertTrue(t.onSpeed(70f, 4_000, 40.0))
    }

    @Test
    fun spikeBelowLimitResetsSustain() {
        val t = tracker()
        t.onLimitSign(60)
        t.onSpeed(70f, 0, 0.0)
        t.onSpeed(55f, 2_000, 20.0)
        assertFalse(t.onSpeed(70f, 4_000, 40.0))
        assertTrue(t.onSpeed(70f, 7_000, 70.0))
    }

    @Test
    fun repeatsOnlyAfterRepeatInterval() {
        val t = tracker()
        t.onLimitSign(50)
        t.onSpeed(60f, 0, 0.0)
        assertTrue(t.onSpeed(60f, 3_000, 30.0))
        assertFalse(t.onSpeed(60f, 10_000, 100.0))
        assertTrue(t.onSpeed(60f, 23_000, 230.0))
    }

    @Test
    fun newSignReplacesLimit() {
        val t = tracker()
        t.onLimitSign(50)
        t.onSpeed(60f, 0, 0.0)
        t.onLimitSign(80)
        assertEquals(80, t.currentLimit)
        assertFalse(t.onSpeed(60f, 5_000, 50.0))
    }

    @Test
    fun limitExpiresAfterDistance() {
        val t = tracker()
        t.onSpeed(40f, 0, 1_000.0)
        t.onLimitSign(50) // đặt tại km 1
        t.onSpeed(40f, 1_000, 2_900.0)
        assertEquals(50, t.currentLimit)
        t.onSpeed(40f, 2_000, 3_100.0)
        assertNull(t.currentLimit)
        assertFalse(t.onSpeed(90f, 9_000, 3_200.0))
    }
}
