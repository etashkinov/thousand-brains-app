package com.eta.tbp.lib.cmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CmpMessageTest {
    private fun sampleMessage(senderType: SenderType = SenderType.SM) =
        CmpMessage(
            location = floatArrayOf(1f, 2f),
            morphologicalFeatures =
                MorphologicalFeatures(
                    poseVectors = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
                    poseFullyDefined = true,
                ),
            nonMorphologicalFeatures = mapOf("primitive_type" to "line"),
            confidence = 0.9f,
            passMessage = true,
            senderId = "sm-0",
            senderType = senderType,
            processFeaturesInLm = true,
        )

    @Test
    fun `getFeatureByName reads from non-morphological features`() {
        val message = sampleMessage()
        assertEquals("line", message.getFeatureByName("primitive_type"))
        assertNull(message.getFeatureByName("missing"))
    }

    @Test
    fun `getPoseVectors returns the morphological pose vectors`() {
        val message = sampleMessage()
        assertEquals(2, message.getPoseVectors()?.size)
    }

    @Test
    fun `getPoseVectors is null when morphological features are absent`() {
        val message = sampleMessage().copyWithoutMorphology()
        assertNull(message.getPoseVectors())
    }

    @Test
    fun `isFromSm reflects sender type`() {
        assertTrue(sampleMessage(SenderType.SM).isFromSm())
        assertFalse(sampleMessage(SenderType.LM).isFromSm())
    }

    private fun CmpMessage.copyWithoutMorphology() =
        CmpMessage(
            location = location,
            morphologicalFeatures = null,
            nonMorphologicalFeatures = nonMorphologicalFeatures,
            confidence = confidence,
            passMessage = passMessage,
            senderId = senderId,
            senderType = senderType,
            processFeaturesInLm = processFeaturesInLm,
        )
}
