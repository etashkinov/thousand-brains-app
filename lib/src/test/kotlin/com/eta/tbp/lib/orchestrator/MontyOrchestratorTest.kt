package com.eta.tbp.lib.orchestrator

import com.eta.tbp.lib.lm.CharacterGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import com.eta.tbp.lib.sensor.PrimitiveSensorModule
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.TouchSensorModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MontyOrchestratorTest {
    private fun newOrchestrator(memory: GraphMemory = GraphMemory()): MontyOrchestrator {
        val sensor = TouchSensorModule(sensorId = "touch-0")
        val primitiveSensor = PrimitiveSensorModule(sensorId = "primitive-0")
        val tier2 = CharacterGraphLM(lmId = "character-0", memory = memory)
        return MontyOrchestrator(sensor, primitiveSensor, tier2)
    }

    private fun teachCharacter(
        orchestrator: MontyOrchestrator,
        strokes: List<List<RawPoint>>,
        label: String,
    ) {
        orchestrator.beginCharacter()
        strokes.forEach { orchestrator.stepStroke(it) }
        orchestrator.endCharacter()
        orchestrator.teach(label)
    }

    private fun recognize(
        orchestrator: MontyOrchestrator,
        strokes: List<List<RawPoint>>,
    ): RecognitionResult {
        orchestrator.beginCharacter()
        strokes.forEach { orchestrator.stepStroke(it) }
        return orchestrator.endCharacter()
    }

    private fun lineShape(): List<RawPoint> = List(30) { i -> RawPoint(i.toFloat(), i.toFloat()) }

    private fun lShape(pointsPerLeg: Int = 20): List<RawPoint> {
        val step = 19f / (pointsPerLeg - 1)
        val down = List(pointsPerLeg) { i -> RawPoint(0f, i * step) }
        val right = List(pointsPerLeg) { i -> RawPoint(i * step, 19f) }
        return down + right.drop(1)
    }

    private fun arcShape(): List<RawPoint> =
        List(30) { i ->
            val angle = PI * i / 29
            RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
        }

    @Test
    fun `single-stroke recognition through the orchestrator matches direct-driving parity`() {
        val orchestrator = newOrchestrator()
        teachCharacter(orchestrator, listOf(lineShape()), "line")
        teachCharacter(orchestrator, listOf(lShape()), "L")
        teachCharacter(orchestrator, listOf(arcShape()), "arc")

        val freshL = lShape(pointsPerLeg = 35).map { it * 2f }
        val result = recognize(orchestrator, listOf(freshL))

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        result as RecognitionResult.Recognized
        assertEquals("L", result.label)
        assertTrue("expected a strong match but was ${result.confidence}", result.confidence > 0.9f)
    }

    @Test
    fun `a taught corner shape is not confused with an unrelated arc through the real orchestrator`() {
        // Regression test for a curvature-scale bug specific to the
        // orchestrator: buildNormalizedObservations() used to compute
        // curvature on each stroke's pre-normalization points as an
        // optimization that was valid for a bare turning-angle curvature
        // (rotation/scale-invariant) but silently wrong once curvature
        // became real, length-normalized differential curvature (which
        // scales inversely with whatever coordinate space it's measured
        // in). lShape() drawn at its small raw pixel scale measured a
        // corner curvature roughly 13x smaller than it should have in the
        // shared normalized space, so PrimitiveSensorModule's sharp-corner
        // veto never tripped -- "L" got taught as [ARC, LINE] instead of
        // [LINE, LINE], and a completely unrelated arcShape() (one ARC
        // node) then spuriously matched "L"'s corrupted ARC node instead of
        // scoring Unknown. This only reproduces through the real
        // orchestrator (not by driving PrimitiveSensorModule directly),
        // since it's specifically about which coordinate space curvature
        // gets computed in during buildNormalizedObservations().
        val orchestrator = newOrchestrator()
        teachCharacter(orchestrator, listOf(lineShape()), "line")
        teachCharacter(orchestrator, listOf(lShape()), "L")

        val result = recognize(orchestrator, listOf(arcShape()))
        assertEquals(RecognitionResult.Unknown, result)
    }

    @Test
    fun `a genuine two-stroke character is recognized as one character from a fresh two-stroke instance`() {
        val orchestrator = newOrchestrator()
        val vertical = List(20) { i -> RawPoint(10f, i.toFloat()) }
        val horizontal = List(20) { i -> RawPoint(i.toFloat(), 10f) }
        teachCharacter(orchestrator, listOf(vertical, horizontal), "+")

        val freshVertical = List(30) { i -> RawPoint(5f, i * (10f / 29f)) }
        val freshHorizontal = List(30) { i -> RawPoint(i * (10f / 29f), 5f) }
        val result = recognize(orchestrator, listOf(freshVertical, freshHorizontal))

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("+", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `shared whole-character normalization discriminates cross-stroke position, not just per-stroke shape`() {
        // Both labels are structurally identical primitive-type sequences (line,
        // line) with the same relative turn angle -- the only thing that can tell
        // them apart is where the two strokes sit relative to each other, which
        // requires normalizing across the whole character rather than per stroke.
        val orchestrator = newOrchestrator()
        val vertical = List(20) { i -> RawPoint(10f, i.toFloat()) }
        val overlappingHorizontal = List(20) { i -> RawPoint(i.toFloat(), 10f) }
        teachCharacter(orchestrator, listOf(vertical, overlappingHorizontal), "plus")

        val farHorizontal = List(20) { i -> RawPoint(200f + i, 210f) }
        teachCharacter(orchestrator, listOf(vertical, farHorizontal), "offset")

        val freshVertical = List(30) { i -> RawPoint(5f, i * (10f / 29f)) }
        val freshOverlappingHorizontal = List(30) { i -> RawPoint(i * (10f / 29f), 5f) }
        val result = recognize(orchestrator, listOf(freshVertical, freshOverlappingHorizontal))

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("plus", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `undoLastStroke drops the last stroke and recomputes as if it was never drawn`() {
        val orchestrator = newOrchestrator()
        teachCharacter(orchestrator, listOf(lShape()), "L")

        orchestrator.beginCharacter()
        orchestrator.stepStroke(lShape())
        orchestrator.stepStroke(lineShape()) // bogus extra stroke
        orchestrator.undoLastStroke()
        val result = orchestrator.endCharacter()

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("L", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `clearCharacter resets the in-progress character to Unknown`() {
        val orchestrator = newOrchestrator()
        teachCharacter(orchestrator, listOf(lShape()), "L")

        orchestrator.beginCharacter()
        orchestrator.stepStroke(lShape())
        orchestrator.clearCharacter()
        val result = orchestrator.endCharacter()

        assertEquals(RecognitionResult.Unknown, result)
    }

    @Test
    fun `teach-then-recognize-then-ambiguous-pair sequence through the real orchestrator`() {
        val orchestrator = newOrchestrator()

        assertEquals(RecognitionResult.Unknown, recognize(orchestrator, listOf(lineShape())))
        orchestrator.teach("line")

        assertEquals(RecognitionResult.Unknown, recognize(orchestrator, listOf(lShape())))
        orchestrator.teach("L")

        assertEquals(RecognitionResult.Unknown, recognize(orchestrator, listOf(arcShape())))
        orchestrator.teach("arc")

        val freshL = lShape(pointsPerLeg = 35).map { it * 2f }
        val recognized = recognize(orchestrator, listOf(freshL))
        assertTrue("expected Recognized but was $recognized", recognized is RecognitionResult.Recognized)
        assertEquals("L", (recognized as RecognitionResult.Recognized).label)

        // An exact duplicate of lShape() taught under a new label creates a genuine tie.
        recognize(orchestrator, listOf(lShape()))
        orchestrator.teach("L2")

        val ambiguous = recognize(orchestrator, listOf(freshL))
        assertTrue("expected Ambiguous but was $ambiguous", ambiguous is RecognitionResult.Ambiguous)
        assertEquals(setOf("L", "L2"), (ambiguous as RecognitionResult.Ambiguous).labels.toSet())
    }

    @Test
    fun `currentPrimitiveOverlays reports a bounding box in raw touch coordinates, not normalized space`() {
        // lineShape() runs from (0,0) to (29,29) in raw touch pixels -- the
        // overlay's bounds should track that original coordinate space
        // directly (mapped back through the same resample+smooth points
        // used before normalization), not the [-1, 1]-ish normalized space
        // GraphNode.location lives in.
        val orchestrator = newOrchestrator()
        orchestrator.beginCharacter()
        orchestrator.stepStroke(lineShape())

        val overlays = orchestrator.currentPrimitiveOverlays()
        assertEquals(1, overlays.size)
        val overlay = overlays.single()
        assertTrue("expected a Line measurement but was ${overlay.measurement}", overlay.measurement is PrimitiveMeasurement.Line)
        assertEquals(0f, overlay.topLeft.x, 1f)
        assertEquals(0f, overlay.topLeft.y, 1f)
        assertEquals(29f, overlay.bottomRight.x, 1f)
        assertEquals(29f, overlay.bottomRight.y, 1f)
    }

    @Test
    fun `currentPrimitiveOverlays has one entry per detected primitive and clears with the character`() {
        val orchestrator = newOrchestrator()
        orchestrator.beginCharacter()
        orchestrator.stepStroke(lShape())
        assertEquals(2, orchestrator.currentPrimitiveOverlays().size)

        orchestrator.clearCharacter()
        assertTrue(orchestrator.currentPrimitiveOverlays().isEmpty())
    }
}
