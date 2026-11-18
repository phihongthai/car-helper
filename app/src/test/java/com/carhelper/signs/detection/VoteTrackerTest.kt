package com.carhelper.signs.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class VoteTrackerTest {

    @Test
    fun `announces only after min hits within window`() {
        val v = VoteTracker(window = 5, minHits = 3, cooldownMs = 10_000)
        assertEquals(emptyList<Int>(), v.update(setOf(7), 0))
        assertEquals(emptyList<Int>(), v.update(setOf(7), 200))
        assertEquals(listOf(7), v.update(setOf(7), 400))
    }

    @Test
    fun `occluded frames still count when hits fall inside window`() {
        val v = VoteTracker(window = 5, minHits = 3, cooldownMs = 10_000)
        v.update(setOf(1), 0)
        v.update(emptySet(), 200) // bị xe khác che
        v.update(setOf(1), 400)
        v.update(emptySet(), 600)
        assertEquals(listOf(1), v.update(setOf(1), 800))
    }

    @Test
    fun `old hits slide out of window`() {
        val v = VoteTracker(window = 3, minHits = 3, cooldownMs = 10_000)
        v.update(setOf(2), 0)
        v.update(setOf(2), 200)
        v.update(emptySet(), 400)
        assertEquals(emptyList<Int>(), v.update(setOf(2), 600))
    }

    @Test
    fun `does not repeat within cooldown and repeats after it`() {
        val v = VoteTracker(window = 3, minHits = 1, cooldownMs = 1_000)
        assertEquals(listOf(5), v.update(setOf(5), 0))
        assertEquals(emptyList<Int>(), v.update(setOf(5), 500))
        assertEquals(listOf(5), v.update(setOf(5), 1_000))
    }

    @Test
    fun `single noisy frame is ignored`() {
        val v = VoteTracker(window = 5, minHits = 3, cooldownMs = 10_000)
        assertEquals(emptyList<Int>(), v.update(setOf(9), 0))
        repeat(4) { assertEquals(emptyList<Int>(), v.update(emptySet(), 200L * (it + 1))) }
    }
}
