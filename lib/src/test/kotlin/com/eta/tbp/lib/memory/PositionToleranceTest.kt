package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.FloatLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionToleranceTest {
    @Test
    fun `isNear is true within tolerance, including exact equality`() {
        val origin = FloatLocation(0f, 0f)
        assertTrue(PositionTolerance.EXACT.isNear(origin, FloatLocation(0f, 0f)))
        assertTrue(PositionTolerance(0.3f).isNear(origin, FloatLocation(0.2f, 0f)))
    }

    @Test
    fun `isNear is false outside tolerance`() {
        val origin = FloatLocation(0f, 0f)
        assertFalse(PositionTolerance(0.3f).isNear(origin, FloatLocation(0.5f, 0f)))
    }

    @Test
    fun `isNear is always false against Location Infinity for any finite tolerance`() {
        assertFalse(PositionTolerance(Float.MAX_VALUE).isNear(Location.Infinity, FloatLocation(0f, 0f)))
    }

    @Test
    fun `anyNear is true when some element in the collection is near`() {
        val visited = setOf(FloatLocation(0f, 0f), FloatLocation(5f, 5f))
        assertTrue(PositionTolerance(0.3f).anyNear(visited, FloatLocation(5.1f, 5f)))
    }

    @Test
    fun `anyNear is false when nothing in the collection is near`() {
        val visited = setOf(FloatLocation(0f, 0f), FloatLocation(5f, 5f))
        assertFalse(PositionTolerance(0.3f).anyNear(visited, FloatLocation(2.5f, 2.5f)))
    }
}
