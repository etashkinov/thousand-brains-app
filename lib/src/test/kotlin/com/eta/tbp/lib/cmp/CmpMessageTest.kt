package com.eta.tbp.lib.cmp

import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.PrimitiveFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmpMessageTest {
    private fun sampleMessage(senderType: SenderType = SenderType.SM) =
        CmpMessage(
            location = FloatLocation(1f, 2f),
            feature = PrimitiveFeature(label = "line", angle = 0f, extent = 1f),
            confidence = 0.9f,
            passMessage = true,
            senderId = "sm-0",
            senderType = senderType,
            processFeaturesInLm = true,
        )

    @Test
    fun `carries the producer's own location and feature payload`() {
        val message = sampleMessage()
        assertEquals(FloatLocation(1f, 2f), message.location)
        assertEquals(PrimitiveFeature(label = "line", angle = 0f, extent = 1f), message.feature)
    }

    @Test
    fun `location and feature are optional`() {
        val message = sampleMessage().let { it.copy(location = null, feature = null) }
        assertEquals(null, message.location)
        assertEquals(null, message.feature)
    }

    @Test
    fun `isFromSm reflects sender type`() {
        assertTrue(sampleMessage(SenderType.SM).isFromSm())
        assertFalse(sampleMessage(SenderType.LM).isFromSm())
    }

    private fun CmpMessage.copy(
        location: com.eta.tbp.lib.memory.Location? = this.location,
        feature: com.eta.tbp.lib.memory.Feature? = this.feature,
    ) = CmpMessage(
        location = location,
        feature = feature,
        confidence = confidence,
        passMessage = passMessage,
        senderId = senderId,
        senderType = senderType,
        processFeaturesInLm = processFeaturesInLm,
    )
}
