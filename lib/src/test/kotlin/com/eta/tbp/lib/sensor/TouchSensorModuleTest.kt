package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.SenderType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSensorModuleTest {
    private val sensorModule = TouchSensorModule(sensorId = "touch-0")

    @Test
    fun `step converts a raw observation into a CmpMessage with matching fields`() {
        val observation =
            RawTouchObservation(
                position = floatArrayOf(0.5f, -0.25f),
                tangentAngle = 0.3f,
                curvature = 0.1f,
                strokeIndex = 1,
                orderInStroke = 4,
            )

        val message = sensorModule.step(observation)

        assertArrayEquals(observation.position, message.location, 1e-6f)
        assertEquals("touch-0", message.senderId)
        assertEquals(SenderType.SM, message.senderType)
        assertTrue(message.isFromSm())
        assertEquals(1f, message.confidence, 1e-6f)
        assertTrue(message.passMessage)
        assertTrue(message.processFeaturesInLm)
        assertEquals(0.1f, message.getFeatureByName("curvature"))
        assertEquals(1, message.getFeatureByName("stroke_index"))
        assertEquals(4, message.getFeatureByName("order_in_stroke"))
        assertTrue(message.morphologicalFeatures?.poseFullyDefined == true)
    }

    @Test
    fun `step defensively copies the position array`() {
        val position = floatArrayOf(1f, 2f)
        val observation = RawTouchObservation(position, 0f, 0f, 0, 0)

        val message = sensorModule.step(observation)
        position[0] = 999f

        assertEquals(1f, message.location!![0], 1e-6f)
    }
}
