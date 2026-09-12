package com.eta.tbp.lib.city

import com.eta.tbp.lib.sensor.LabelFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelFeatureTest {
    @Test
    fun `same label is zero difference`() {
        assertEquals(0f, LabelFeature("park").difference(LabelFeature("park")), 1e-6f)
    }

    @Test
    fun `different label is an infinite difference`() {
        assertTrue(LabelFeature("park").difference(LabelFeature("bakery")).isInfinite())
    }

    @Test
    fun `mergedWith is a no-op`() {
        val feature = LabelFeature("park")
        assertEquals(feature, feature.mergedWith(LabelFeature("park"), selfWeight = 3f, totalWeight = 4f))
    }
}
