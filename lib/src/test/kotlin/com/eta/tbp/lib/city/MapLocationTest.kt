package com.eta.tbp.lib.city

import com.eta.tbp.lib.memory.Location
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLocationTest {
    @Test
    fun `displacement is component-wise subtraction`() {
        assertEquals(MapLocation(2, -1), MapLocation(5, 2).displacement(MapLocation(3, 3)))
    }

    @Test
    fun `displacement from a non-MapLocation is Infinity`() {
        assertEquals(Location.Infinity, MapLocation(1, 1).displacement(Location.Infinity))
    }

    @Test
    fun `magnitude is the Euclidean length`() {
        assertEquals(5f, MapLocation(3, 4).magnitude(), 1e-6f)
    }

    @Test
    fun `mergedWith rounds the weighted average back to a cell`() {
        val merged = MapLocation(0, 0).mergedWith(MapLocation(3, 1), selfWeight = 1f, totalWeight = 2f)
        assertEquals(MapLocation(2, 1), merged) // (0+3)/2 = 1.5 -> 2, (0+1)/2 = 0.5 -> 1 (round-half-up)
    }
}
