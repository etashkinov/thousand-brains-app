package com.eta.tbp.lib.city

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFeatureTest {
    @Test
    fun `same label is zero difference`() {
        assertEquals(0f, MapFeature("park").difference(MapFeature("park")), 1e-6f)
    }

    @Test
    fun `different label is an infinite difference`() {
        assertTrue(MapFeature("park").difference(MapFeature("bakery")).isInfinite())
    }

    @Test
    fun `mergedWith is a no-op`() {
        val feature = MapFeature("park")
        assertEquals(feature, feature.mergedWith(MapFeature("park"), selfWeight = 3f, totalWeight = 4f))
    }

    @Test
    fun `EMPTY is a distinct, stable feature`() {
        assertEquals(MapFeature("empty"), MapFeature.EMPTY)
    }
}
