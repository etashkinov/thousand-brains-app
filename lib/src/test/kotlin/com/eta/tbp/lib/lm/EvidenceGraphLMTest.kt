package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.PrimitiveFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphLMTest {
    private fun newLm(id: String = "lm-0") = EvidenceGraphLM(lmId = id)

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
        val evidenceGraphLM = newLm()

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
    fun `possibleMatches and recognitionResult are Unknown before anything is taught or drawn`() {
        val evidenceGraphLM = newLm()

        assertTrue(possibleMatches(evidenceGraphLM.evidenceSnapshot()).isEmpty())
        assertEquals(RecognitionResult.Unknown, evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `a confidently recognized shape reports exactly one possible match`() {
        val evidenceGraphLM = newLm()

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, arcShape())
        evidenceGraphLM.teach("arc")

        drive(evidenceGraphLM, lShape(originX = 50f, originY = -20f))
        val evidence = evidenceGraphLM.evidenceSnapshot()

        assertEquals(listOf("L"), possibleMatches(evidenceGraphLM.evidenceSnapshot()))
        assertEquals(RecognitionResult.Recognized("L", evidence.getValue("L")), evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `two structurally identical shapes taught under different labels tie`() {
        val evidenceGraphLM = newLm()

        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, lShape(originX = 100f, originY = 100f))
        evidenceGraphLM.teach("L2")

        drive(evidenceGraphLM, lShape(originX = -30f, originY = 5f))

        assertEquals(setOf("L", "L2"), possibleMatches(evidenceGraphLM.evidenceSnapshot()).toSet())
        val result = evidenceGraphLM.recognitionResult()
        assertTrue("expected Ambiguous but was $result", result is RecognitionResult.Ambiguous)
        assertEquals(setOf("L", "L2"), (result as RecognitionResult.Ambiguous).labels.toSet())
    }

    @Test
    fun `proposeGoal proposes where the tied hypotheses disagree, using this LM's own memory and observations`() {
        val evidenceGraphLM = newLm()

        // "L" and "L2" agree on their first two nodes but differ in the third.
        drive(evidenceGraphLM, listOf(message(0f, 0f, "line"), message(0f, 1f, "line"), message(1f, 1f, "arc")))
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, listOf(message(0f, 0f, "line"), message(0f, 1f, "line"), message(1f, -1f, "arc")))
        evidenceGraphLM.teach("L2")

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(50f, 50f, "line")))
        evidenceGraphLM.matchingStep(listOf(message(50f, 51f, "line")))
        assertTrue(evidenceGraphLM.recognitionResult() is RecognitionResult.Ambiguous)

        val goal = evidenceGraphLM.proposeGoal()

        assertTrue(
            "expected one candidate's own predicted arc location but was ${goal?.location}",
            goal?.location == FloatLocation(51f, 51f) || goal?.location == FloatLocation(51f, 49f),
        )
        assertEquals(SenderType.GSG, goal?.senderType)
        assertTrue(goal?.passMessage == true)
    }

    @Test
    fun `proposeGoal is null when the result isn't Ambiguous`() {
        val evidenceGraphLM = newLm()
        assertEquals(null, evidenceGraphLM.proposeGoal())
    }

    @Test
    fun `partial evidence during an in-progress episode narrows down as more nodes arrive`() {
        val evidenceGraphLM = newLm()

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(10f, 10f, "line")))
        // One "line"-labeled node alone is consistent with both taught shapes.
        assertEquals(setOf("line", "L"), possibleMatches(evidenceGraphLM.evidenceSnapshot()).toSet())

        evidenceGraphLM.matchingStep(listOf(message(10f, 11f, "line")))
        // A second "line" node at the L's own relative offset only continues to fit "L".
        assertEquals(listOf("L"), possibleMatches(evidenceGraphLM.evidenceSnapshot()))
    }

    @Test
    fun `an unrecognized first node doesn't stop a later, recognized node from anchoring the match`() {
        val evidenceGraphLM = newLm()

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        evidenceGraphLM.preEpisode()
        // "totally-novel-shape" doesn't exist anywhere in memory -- if it monopolized the anchor slot, "line" would be stuck at zero evidence for the rest of the episode.
        evidenceGraphLM.matchingStep(listOf(message(500f, 500f, "totally-novel-shape")))
        evidenceGraphLM.matchingStep(lineShape(originX = 40f, originY = 40f))

        assertEquals(listOf("line"), possibleMatches(evidenceGraphLM.evidenceSnapshot()))
    }

    @Test
    fun `teaching still records every observed node even when none of them matched anything taught`() {
        val evidenceGraphLM = newLm()

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        // A shape sharing no feature at all with anything taught -- should still be teachable as its own new object.
        drive(evidenceGraphLM, listOf(message(0f, 0f, "square"), message(1f, 0f, "square"), message(1f, 1f, "square")))
        evidenceGraphLM.teach("square")

        assertEquals(setOf("line", "square"), evidenceGraphLM.state().keys)
        assertEquals(
            3,
            evidenceGraphLM
                .state()
                .getValue("square")
                .single()
                .nodes.size,
        )
    }

    @Test
    fun `teach is a no-op in EVALUATE mode but works again once switched back to TRAIN`() {
        val evidenceGraphLM = newLm()

        evidenceGraphLM.setExperimentMode(ExperimentMode.EVALUATE)
        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        assertEquals(emptySet<String>(), evidenceGraphLM.state().keys)

        evidenceGraphLM.setExperimentMode(ExperimentMode.TRAIN)
        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        assertEquals(setOf("line"), evidenceGraphLM.state().keys)
    }

    @Test
    fun `state captures taught labels and loadState restores them into a fresh instance`() {
        val evidenceGraphLM = newLm()

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
    fun `checkedLocationsInOrder reports locations in visit order, not hash order`() {
        val evidenceGraphLM = newLm()

        // Chosen so a plain (unordered) Set's own iteration order is unlikely to match visit order by chance.
        val visitOrder = listOf(FloatLocation(9f, 9f), FloatLocation(0f, 0f), FloatLocation(5f, 2f), FloatLocation(1f, 8f))
        evidenceGraphLM.preEpisode()
        visitOrder.forEach { location ->
            evidenceGraphLM.matchingStep(listOf(message(location.location[0], location.location[1], "post_office")))
        }

        assertEquals(visitOrder, evidenceGraphLM.checkedLocationsInOrder())
    }

    @Test
    fun `revisiting an already-checked location doesn't add a second entry to checkedLocationsInOrder`() {
        val evidenceGraphLM = newLm()

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(0f, 0f, "post_office")))
        evidenceGraphLM.matchingStep(listOf(message(1f, 1f, "park")))
        evidenceGraphLM.matchingStep(listOf(message(0f, 0f, "post_office")))

        assertEquals(listOf(FloatLocation(0f, 0f), FloatLocation(1f, 1f)), evidenceGraphLM.checkedLocationsInOrder())
    }

    @Test
    fun `getOutput's confidence matches the top evidence value once something is taught and observed`() {
        val evidenceGraphLM = newLm()

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lineShape(originX = 40f, originY = 40f))

        val output = requireNotNull(evidenceGraphLM.getOutput())
        assertEquals(evidenceGraphLM.evidenceSnapshot().getValue("line"), output.confidence, 1e-6f)
    }

    @Test
    fun `a taught city is not recognized from a foreign landmark plus a single shared one`() {
        val evidenceGraphLM = newLm()
        drive(
            evidenceGraphLM,
            listOf(
                message(0f, 0f, "city-hall"),
                message(1f, 0f, "bakery"),
                message(2f, 0f, "town-hall"),
                message(3f, 0f, "power-plant"),
                message(4f, 0f, "school"),
            ),
        )
        evidenceGraphLM.teach("Springfield")

        // A different city that happens to share exactly one landmark's label with Springfield.
        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(9f, 9f, "cinema")))
        evidenceGraphLM.matchingStep(listOf(message(10f, 9f, "bakery")))

        assertEquals(RecognitionResult.Unknown, evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `a single matching observation isn't enough to recognize a multi-node object`() {
        val evidenceGraphLM = newLm()
        drive(evidenceGraphLM, listOf(message(0f, 0f, "city-hall"), message(1f, 0f, "bakery")))
        evidenceGraphLM.teach("Springfield")

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(50f, 50f, "city-hall")))

        assertEquals(RecognitionResult.Unknown, evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `a single observation is enough to recognize a genuinely 1-node object`() {
        val evidenceGraphLM = newLm()
        drive(evidenceGraphLM, listOf(message(0f, 0f, "lighthouse")))
        evidenceGraphLM.teach("SoloIsland")

        evidenceGraphLM.preEpisode()
        evidenceGraphLM.matchingStep(listOf(message(50f, 50f, "lighthouse")))

        assertEquals(RecognitionResult.Recognized("SoloIsland", 1f), evidenceGraphLM.recognitionResult())
    }
}
