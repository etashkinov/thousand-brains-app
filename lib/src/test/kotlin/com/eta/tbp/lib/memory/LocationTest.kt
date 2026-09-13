package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.FloatLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTest {
    @Test
    fun `isNear is true within tolerance, including exact equality`() {
        val origin = FloatLocation(0f, 0f)
        assertTrue(origin.isNear(FloatLocation(0f, 0f), 0f))
        assertTrue(origin.isNear(FloatLocation(0.2f, 0f), 0.3f))
    }

    @Test
    fun `isNear is false outside tolerance`() {
        val origin = FloatLocation(0f, 0f)
        assertFalse(origin.isNear(FloatLocation(0.5f, 0f), 0.3f))
    }

    @Test
    fun `isNear is always false against Location Infinity for any finite tolerance`() {
        assertFalse(Location.Infinity.isNear(FloatLocation(0f, 0f), Float.MAX_VALUE))
    }

    @Test
    fun `anyNear is true when some element in the collection is near`() {
        val visited = setOf(FloatLocation(0f, 0f), FloatLocation(5f, 5f))
        assertTrue(visited.anyNear(FloatLocation(5.1f, 5f), 0.3f))
    }

    @Test
    fun `anyNear is false when nothing in the collection is near`() {
        val visited = setOf(FloatLocation(0f, 0f), FloatLocation(5f, 5f))
        assertFalse(visited.anyNear(FloatLocation(2.5f, 2.5f), 0.3f))
    }
}
