package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.StrokePreprocessor
import com.eta.tbp.lib.util.angleDifference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class EvidenceGraphLMTest {
    /** One primitive's raw (un-normalized) endpoints and taught label — a "shape" under test is a sequence of these. */
    private data class PrimitiveSpec(
        val label: String,
        val start: RawPoint,
        val end: RawPoint,
    )

    private fun featureOf(message: CmpMessage): PrimitiveMeasurement = (message.nonMorphologicalFeatures as PrimitiveFeatures).measurement

    private fun newLm(
        id: String = "character-0",
        memory: GraphMemory<PrimitiveMeasurement> = GraphMemory(),
    ) = EvidenceGraphLM(lmId = id, memory = memory, featureOf = ::featureOf)

    /**
     * Builds and drives the exact `CmpMessage` sequence
     * [com.eta.tbp.lib.orchestrator.MontyOrchestrator] would emit for
     * [primitives], without depending on any actual segmentation layer —
     * this file is about [EvidenceGraphLM]'s own matching/teaching logic,
     * which [PrimitiveGraphLM]/`StrokeSegmenter` already have their own
     * dedicated tests for. All endpoints are normalized together first (as
     * the real orchestrator does across a whole character), so
     * scale/position comparisons behave the same way real drawing input
     * would.
     */
    private fun drive(
        evidenceGraphLM: EvidenceGraphLM<PrimitiveMeasurement>,
        primitives: List<PrimitiveSpec>,
    ) {
        val normalized = StrokePreprocessor.normalize(primitives.flatMap { listOf(it.start, it.end) })

        evidenceGraphLM.preEpisode()
        var previousExitAngle = 0f
        for ((index, spec) in primitives.withIndex()) {
            val start = normalized[index * 2]
            val end = normalized[index * 2 + 1]
            val absoluteAngle = atan2(end.y - start.y, end.x - start.x)
            val relativeAngle = angleDifference(absoluteAngle, previousExitAngle)
            previousExitAngle = absoluteAngle

            val message =
                CmpMessage(
                    location = floatArrayOf((start.x + end.x) / 2f, (start.y + end.y) / 2f),
                    morphologicalFeatures = MorphologicalFeatures(poseVectors = rotationBasis(relativeAngle), poseFullyDefined = true),
                    nonMorphologicalFeatures =
                        object : PrimitiveFeatures {
                            override val measurement = PrimitiveMeasurement(label = spec.label, extent = (end - start).length())
                            override val startIndex = index * 2
                            override val endIndex = index * 2 + 1
                            override val strokeIndex = 0
                        },
                    confidence = 1f,
                    passMessage = true,
                    senderId = "test",
                    senderType = SenderType.SM,
                    processFeaturesInLm = true,
                )
            evidenceGraphLM.matchingStep(listOf(message))
        }
        evidenceGraphLM.postEpisode()
    }

    private fun rotationBasis(angle: Float): Array<FloatArray> {
        val cosA = cos(angle)
        val sinA = sin(angle)
        return arrayOf(floatArrayOf(cosA, sinA), floatArrayOf(-sinA, cosA))
    }

    private fun lineShape(scale: Float = 1f): List<PrimitiveSpec> =
        listOf(
            PrimitiveSpec(
                "line",
                RawPoint(0f, 0f),
                RawPoint(30f, 30f) * scale,
            ),
        )

    private fun lShape(scale: Float = 1f): List<PrimitiveSpec> =
        listOf(
            PrimitiveSpec("line", RawPoint(0f, 0f), RawPoint(0f, 19f) * scale),
            PrimitiveSpec("line", RawPoint(0f, 19f) * scale, RawPoint(19f, 19f) * scale),
        )

    private fun arcShape(scale: Float = 1f): List<PrimitiveSpec> =
        listOf(
            PrimitiveSpec(
                "arc",
                RawPoint(1f, 0f) * scale,
                RawPoint(-1f, 0f) * scale,
            ),
        )

    @Test
    fun `a fresh instance of a taught shape scores its own label highest`() {
        val memory = GraphMemory<PrimitiveMeasurement>()
        val evidenceGraphLM = newLm(memory = memory)

        // Teach three structurally distinct shapes.
        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")

        drive(evidenceGraphLM, arcShape())
        evidenceGraphLM.teach("arc")

        // A *fresh* instance of "L", at a different scale.
        drive(evidenceGraphLM, lShape(scale = 2f))
        val evidence = evidenceGraphLM.evidenceSnapshot()

        assertTrue("expected all 3 labels scored: $evidence", evidence.keys.containsAll(setOf("line", "L", "arc")))
        assertEquals("L", evidence.maxByOrNull { it.value }?.key)
        assertTrue("expected a strong match but was ${evidence["L"]}", evidence.getValue("L") > 0.9f)

        // getOutput() collapses to the same top hypothesis evidenceSnapshot() reports,
        // matching Monty's get_output(): a single-hypothesis point estimate, not a map.
        val output = requireNotNull(evidenceGraphLM.getOutput())
        assertEquals("L", output.nonMorphologicalFeatures)
        assertEquals(evidence.getValue("L"), output.confidence, 1e-6f)
    }

    @Test
    fun `currentNodes reflects the buffered episode and clears on preEpisode`() {
        val evidenceGraphLM = newLm()
        assertTrue(evidenceGraphLM.currentNodes().isEmpty())

        drive(evidenceGraphLM, lShape())

        val nodes = evidenceGraphLM.currentNodes()
        assertTrue(
            "expected two line-labeled nodes but got ${nodes.map { it.feature }}",
            nodes.size == 2 && nodes.all { it.feature.label == "line" },
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
        val memory = GraphMemory<PrimitiveMeasurement>()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, arcShape())
        evidenceGraphLM.teach("arc")

        drive(evidenceGraphLM, lShape(scale = 2f))
        val evidence = evidenceGraphLM.evidenceSnapshot()

        assertEquals(listOf("L"), evidenceGraphLM.possibleMatches())
        assertEquals(RecognitionResult.Recognized("L", evidence.getValue("L")), evidenceGraphLM.recognitionResult())
    }

    @Test
    fun `two structurally identical shapes taught under different labels tie`() {
        val memory = GraphMemory<PrimitiveMeasurement>()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L")
        drive(evidenceGraphLM, lShape())
        evidenceGraphLM.teach("L2")

        drive(evidenceGraphLM, lShape(scale = 2f))

        assertEquals(setOf("L", "L2"), evidenceGraphLM.possibleMatches().toSet())
        val result = evidenceGraphLM.recognitionResult()
        assertTrue("expected Ambiguous but was $result", result is RecognitionResult.Ambiguous)
        assertEquals(setOf("L", "L2"), (result as RecognitionResult.Ambiguous).labels.toSet())
    }

    @Test
    fun `state captures taught labels and loadState restores them into a fresh instance`() {
        val memory = GraphMemory<PrimitiveMeasurement>()
        val evidenceGraphLM = newLm(memory = memory)

        drive(evidenceGraphLM, lineShape())
        evidenceGraphLM.teach("line")

        val restored = newLm(id = "character-1")
        restored.loadState(evidenceGraphLM.state())

        assertEquals(evidenceGraphLM.state().keys, restored.state().keys)
    }
}
