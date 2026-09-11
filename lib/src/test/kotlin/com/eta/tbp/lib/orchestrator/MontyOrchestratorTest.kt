@file:Suppress("ktlint:standard:no-empty-file")

package com.eta.tbp.lib.orchestrator

// Disabled along with MontyOrchestrator.kt itself — see that file's note.

/*
import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.PrimitiveGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.sensor.PrimitiveFeature
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import com.eta.tbp.lib.sensor.RawPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MontyOrchestratorTest {
    /** A generic straight-line primitive example — taught once per orchestrator, the same "shapes before characters" curriculum every character-level test relies on. */
    private fun canonicalLine(): List<RawPoint> = List(15) { i -> RawPoint(i.toFloat(), i.toFloat()) }

    /** A generic semicircle primitive example, for the same reason as [canonicalLine]. */
    private fun canonicalArc(): List<RawPoint> =
        List(15) { i ->
            val angle = PI * i / 14
            RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
        }

    private fun featureOf(message: CmpMessage): PrimitiveFeature = (message.nonMorphologicalFeatures as PrimitiveFeatures).measurement

    private fun newOrchestrator(memory: GraphMemory<PrimitiveFeature> = GraphMemory()): MontyOrchestrator {
        val primitiveGraphLM = PrimitiveGraphLM()
        primitiveGraphLM.teach("line", canonicalLine())
        primitiveGraphLM.teach("arc", canonicalArc())
        val tier2 = EvidenceGraphLM(lmId = "character-0", memory = memory, featureOf = ::featureOf)
        return MontyOrchestrator(primitiveGraphLM, tier2)
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
        // Both labels are structurally identical primitive sequences (line,
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
        assertEquals("line", overlay.measurement.label)
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

    // --- Real-drawing regression tests for the bugs that motivated this
    // whole redesign (see IMPLEMENTATION_PLAN.md §7): PrimitiveSensorModule,
    // the hand-coded geometric classifier this replaced, was recalibrated
    // five times chasing these exact three shapes and never converged. All
    // three now go through PrimitiveGraphLM's taught, evidence-matched
    // recognition plus StrokeSegmenter's global search instead of a local,
    // hand-tuned distance threshold.

    @Test
    fun `a small tight hook sharing a stroke with a much longer tail segments as one arc plus one line`() {
        // The concrete repro that first surfaced the old design's structural
        // flaw: a small, tightly-curved hook (radius 70) attached to a much
        // longer, nearly-straight tail (length 400), drawn as one stroke.
        // The hook's curvature, measured in the character's own shared
        // normalized space (set mostly by the much-bigger tail), used to
        // read as "sharp" purely because the tail was so much bigger --
        // no single geometric threshold could accept that and still catch
        // real corners elsewhere. Evidence-based matching doesn't compare
        // a window against the rest of the character at all, only against
        // what's actually been taught, so it isn't affected by how big
        // anything else happens to be.
        val orchestrator = newOrchestrator()
        val hookRadius = 70f
        val hookPointCount = 25
        val hook =
            (0 until hookPointCount).map { i ->
                val theta = PI + PI * i / (hookPointCount - 1)
                RawPoint((hookRadius + hookRadius * cos(theta)).toFloat(), (hookRadius * sin(theta)).toFloat())
            }
        val tailStart = hook.last()
        val tail = (1..60).map { i -> RawPoint(tailStart.x, tailStart.y + 400f * i / 60) }

        orchestrator.beginCharacter()
        orchestrator.stepStroke(hook + tail)

        assertEquals(listOf("arc", "line"), orchestrator.currentPrimitiveOverlays().map { it.measurement.label })
    }

    @Test
    fun `a single gently-curving stroke stays one primitive, not a false corner split`() {
        // The repro from an actual screenshot: a smoothly, continuously
        // curving stroke (no real corner anywhere) used to fragment into
        // two straight lines meeting at a fake kink, because any fixed
        // geometric line/arc fit tolerance has some genuinely intermediate
        // stroke sitting right on its boundary. There's no such boundary to
        // sit on here: the whole stroke either matches something taught
        // well enough to stay whole, or it doesn't -- and StrokeSegmenter's
        // segment-count penalty means it only pays to split when splitting
        // scores meaningfully better, not merely differently.
        val orchestrator = newOrchestrator()
        val points =
            List(60) { i ->
                val t = i / 59f
                val angle = Math.toRadians(35.0) * t
                RawPoint(200f * sin(angle).toFloat(), 600f * t)
            }

        orchestrator.beginCharacter()
        orchestrator.stepStroke(points)

        val overlays = orchestrator.currentPrimitiveOverlays()
        assertEquals("expected one cohesive primitive but got ${overlays.map { it.measurement }}", 1, overlays.size)
    }

    @Test
    fun `a gentle S-curve sharing one stroke's resample budget segments into a small, cohesive handful of primitives`() {
        // The original curvature/resample-density repro: a smooth S drawn
        // as one continuous stroke used to fragment into ~5 degenerate,
        // near-zero-length pieces. What's left here is at most an ordinary
        // line/arc boundary call at the S's inflection point -- a small
        // primitive count with nothing degenerate, not fragmentation.
        val orchestrator = newOrchestrator()
        val amplitude = 60f
        val length = 600f
        val sCurve =
            List(150) { i ->
                val t = i / 149f
                RawPoint(amplitude * sin(2 * PI * t).toFloat(), length * t)
            }

        orchestrator.beginCharacter()
        orchestrator.stepStroke(sCurve)

        val overlays = orchestrator.currentPrimitiveOverlays()
        assertTrue(
            "expected a small, cohesive handful of primitives but got ${overlays.map { it.measurement }}",
            overlays.size in 1..3,
        )
        for (overlay in overlays) {
            assertTrue(
                "expected no degenerate near-zero-extent primitives but found ${overlay.measurement}",
                overlay.measurement.extent > 0.1f,
            )
        }
    }
}
*/
