package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.LabelFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridEnvironmentTest {
    @Test
    fun `featureAt returns the exact cell's feature`() {
        val environment = GridEnvironment.of(size = 10, FloatLocation(5f, 5f) to "post_office")
        assertEquals(LabelFeature("post_office"), environment.featureAt(FloatLocation(5f, 5f)))
    }

    @Test
    fun `featureAt returns the nearby cell's feature when within positionTolerance`() {
        val environment = GridEnvironment.of(size = 10, FloatLocation(5f, 5f) to "post_office", positionTolerance = 0.3f)
        assertEquals(LabelFeature("post_office"), environment.featureAt(FloatLocation(5.2f, 5f)))
    }

    @Test
    fun `featureAt returns null outside positionTolerance`() {
        val environment = GridEnvironment.of(size = 10, FloatLocation(5f, 5f) to "post_office", positionTolerance = 0.3f)
        assertNull(environment.featureAt(FloatLocation(5.5f, 5f)))
    }

    @Test
    fun `featureAt picks the nearest qualifying cell when two are both within tolerance`() {
        val environment =
            GridEnvironment.of(
                size = 10,
                FloatLocation(5f, 5f) to "post_office",
                FloatLocation(5.5f, 5f) to "park",
                positionTolerance = 1f,
            )
        assertEquals(LabelFeature("park"), environment.featureAt(FloatLocation(5.4f, 5f)))
    }
}
