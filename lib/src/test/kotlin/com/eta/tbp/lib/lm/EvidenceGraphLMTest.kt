package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.PrimitiveFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphLMTest {
    private fun newLm(
        id: String = "lm-0",
        memory: GraphMemory = GraphMemory(),
    ) = EvidenceGraphLM(lmId = id, memory = memory)

    private fun message(
        x: Float,
        y: Float,
        label: String,
        extent: Float = 1f,
    ) = CmpMessage(
        location = FloatLocation(x, y),
        feature = PrimitiveFeature(label = label, angle = 0f, extent = extent),
        confidence = 1f,
        passMessage = true,
        senderId = "test",
        senderType = SenderType.SM,
        processFeaturesInLm = true,
    )

    /** Feeds one node per message, in one episode, the same shape a real SM would stream in. */
    private fun drive(
        evidenceGraphLM: EvidenceGraphLM,
        nodes: List<CmpMessage>,
    ) {
        evidenceGraphLM.preEpisode()
        nodes.forEach { evidenceGraphLM.matchingStep(listOf(it)) }
        evidenceGraphLM.postEpisode()
    }

    private fun lineShape(
        originX: Float = 0f,
        originY: Float = 0f,
    ) = listOf(message(originX, originY, "line"), message(originX + 1f, originY + 1f, "line"))

    private fun lShape(
        originX: Float = 0f,
        originY: Float = 0f,
    ) = listOf(
        message(originX, originY, "line"),
        message(originX, originY + 1f, "line"),
        message(originX + 1f, originY + 1f, "arc"),
    )

    private fun arcShape(
        originX: Float = 0f,
        originY: Float = 0f,
    ) = listOf(message(originX, originY, "arc"), message(originX + 2f, originY, "arc"))

    @Test
    fun `a translated instance of a taught shape scores its own label highest`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, arcShape())
        evidenceGraphLM.teach("arc")

        // A fresh instance of "L", moved somewhere else entirely.
        drive(evidenceGraphLM, lShape(originX = 50f, originY = -20f))
        val evidence = evidenceGraphLM.evidenceSnapshot()

        assertTrue("expected all 3 labels scored: $evidence", evidence.keys.containsAll(setOf("line", "L", "arc")))
        assertEquals("L", evidence.maxByOrNull { it.value }?.key)
        assertTrue("expected a strong match but was ${evidence["L"]}", evidence.getValue("L") > 0.9f)
    }

    @Test
    fun `currentNodes reflects the buffered episode and clears on preEpisode`() {
        val evidenceGraphLM = newLm()
        assertTrue(evidenceGraphLM.currentNodes().isEmpty())

        drive(evidenceGraphLM, lShape())

        val nodes = evidenceGraphLM.currentNodes()
        assertTrue(
            "expected two line-labeled nodes then an arc but got ${nodes.map { it.feature }}",
            nodes.size == 3 && nodes.take(2).all { it.feature.label == "line" },
        )

        evidenceGraphLM.preEpisode()
        assertTrue(evidenceGraphLM.currentNodes().isEmpty())
    }

    @Test
    fun `possibleMatches and recognitionResult are Unknown before anything is taught or drawn`() {
        val evidenceGraphLM = newLm()

        assertTrue(evidenceGraphLM.possibleMatches().isEmpty())
        assertEquals(RecognitionResult.Unknown, evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `a confidently recognized shape reports exactly one possible match`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, arcShape())
        evidenceGraphLM.teach("arc")

        drive(evidenceGraphLM, lShape(originX = 50f, originY = -20f))
        val evidence = evidenceGraphLM.evidenceSnapshot()

        assertEquals(listOf("L"), evidenceGraphLM.possibleMatches())
        assertEquals(RecognitionResult.Recognized("L", evidence.getValue("L")), evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `two structurally identical shapes taught under different labels tie`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, lShape(originX = 100f, originY = 100f))
        evidenceGraphLM.teach("L2")

        drive(evidenceGraphLM, lShape(originX = -30f, originY = 5f))

        assertEquals(setOf("L", "L2"), evidenceGraphLM.possibleMatches().toSet())
        val result = evidenceGraphLM.recognitionResult()
        assertTrue("expected Ambiguous but was $result", result is RecognitionResult.Ambiguous)
        assertEquals(setOf("L", "L2"), (result as RecognitionResult.Ambiguous).labels.toSet())
    }

    @Test
    fun `suggestNextLocation proposes where the tied hypotheses disagree, using this LM's own memory and observations`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        // "L" and "L2" agree on their first two nodes but differ in the third.
        drive(evidenceGraphLM, listOf(message(0f, 0f, "line"), message(0f, 1f, "line"), message(1f, 1f, "arc")))
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, listOf(message(0f, 0f, "line"), message(0f, 1f, "line"), message(1f, -1f, "arc")))
        evidenceGraphLM.teach("L2")

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(50f, 50f, "line")))
        evidenceGraphLM.matchingStep(listOf(message(50f, 51f, "line")))
        assertTrue(evidenceGraphLM.recognitionResult() is RecognitionResult.Ambiguous)

        val suggestion = evidenceGraphLM.suggestNextLocation()

        assertTrue(
            "expected one candidate's own predicted arc location but was $suggestion",
            suggestion == FloatLocation(51f, 51f) || suggestion == FloatLocation(51f, 49f),
        )
    }

    @Test
    fun `suggestNextLocation is null when the result isn't Ambiguous`() {
        val evidenceGraphLM = newLm()
        assertEquals(null, evidenceGraphLM.suggestNextLocation())
    }

    @Test
    fun `partial evidence during an in-progress episode narrows down as more nodes arrive`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(10f, 10f, "line")))
        // One "line"-labeled node alone is consistent with both taught shapes.
        assertEquals(setOf("line", "L"), evidenceGraphLM.possibleMatches().toSet())

        evidenceGraphLM.matchingStep(listOf(message(10f, 11f, "line")))
        // A second "line" node at the L's own relative offset only continues to fit "L".
        assertEquals(listOf("L"), evidenceGraphLM.possibleMatches())
    }

    @Test
    fun `an unrecognized first node doesn't stop a later, recognized node from anchoring the match`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        evidenceGraphLM.preEpisode()
        // "totally-novel-shape" doesn't exist anywhere in memory -- if it monopolized the anchor slot, "line" would be stuck at zero evidence for the rest of the episode.
        evidenceGraphLM.matchingStep(listOf(message(500f, 500f, "totally-novel-shape")))
        evidenceGraphLM.matchingStep(lineShape(originX = 40f, originY = 40f))

        assertEquals(listOf("line"), evidenceGraphLM.possibleMatches())
    }

    @Test
    fun `teaching still records every observed node even when none of them matched anything taught`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        // A shape sharing no feature at all with anything taught -- should still be teachable as its own new object.
        drive(evidenceGraphLM, listOf(message(0f, 0f, "square"), message(1f, 0f, "square"), message(1f, 1f, "square")))
        evidenceGraphLM.teach("square")

        assertEquals(setOf("line", "square"), memory.allLabels())
        assertEquals(
            3,
            memory
                .candidatesForLabel("square")
                .single()
                .nodes.size,
        )
    }

    @Test
    fun `teach is a no-op in EVALUATE mode but works again once switched back to TRAIN`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        evidenceGraphLM.setExperimentMode(ExperimentMode.EVALUATE)
        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        assertEquals(emptySet<String>(), memory.allLabels())

        evidenceGraphLM.setExperimentMode(ExperimentMode.TRAIN)
        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        assertEquals(setOf("line"), memory.allLabels())
    }

    @Test
    fun `state captures taught labels and loadState restores them into a fresh instance`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        val restored = newLm(id = "lm-1")
        restored.loadState(evidenceGraphLM.state())

        assertEquals(evidenceGraphLM.state().keys, restored.state().keys)
    }

    @Test
    fun `getOutput is null when nothing has been observed or taught`() {
        val evidenceGraphLM = newLm()
        assertNull(evidenceGraphLM.getOutput())
    }

    @Test
    fun `getOutput's confidence matches the top evidence value once something is taught and observed`() {
        val memory = GraphMemory()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lineShape(originX = 40f, originY = 40f))

        val output = requireNotNull(evidenceGraphLM.getOutput())
        assertEquals(evidenceGraphLM.evidenceSnapshot().getValue("line"), output.confidence, 1e-6f)
    }
}
