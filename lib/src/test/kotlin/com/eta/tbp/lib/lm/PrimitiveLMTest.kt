package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.StrokePreprocessor
import com.eta.tbp.lib.sensor.TouchSensorModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class PrimitiveLMTest {
    private val sensor = TouchSensorModule(sensorId = "touch-0")

    /** Two straight legs of [legLength] points meeting at [interiorAngleDegrees] (180 = perfectly straight, no bend). */
    private fun cornerShape(
        interiorAngleDegrees: Double,
        legLength: Int = 20,
    ): List<RawPoint> {
        val turn = Math.toRadians(180.0 - interiorAngleDegrees)
        val first = List(legLength) { i -> RawPoint(i.toFloat(), 0f) }
        val direction = RawPoint(cos(turn).toFloat(), sin(turn).toFloat())
        val second = List(legLength) { i -> RawPoint(legLength - 1f, 0f) + direction * (i + 1).toFloat() }
        return first + second
    }

    @Test
    fun `corners well short of a right angle still segment as line, corner, line`() {
        // A real hand-drawn corner is rarely a clean right angle -- 90
        // degrees was the only interior angle the original threshold caught
        // reliably. This sweep is the regression test for that gap: each of
        // these interior angles must still produce a discrete CORNER, not a
        // spurious LINE/LINE (or LINE/LINE/LINE) split with the bend
        // silently absorbed into neither segment.
        for (interiorAngle in listOf(90.0, 110.0, 130.0, 150.0)) {
            val shape = cornerShape(interiorAngle)
            val primitives = primitiveTypesOf(drivePrimitives(shape))
            assertEquals(
                "interior angle $interiorAngle degrees",
                listOf("line", "corner", "line"),
                primitives,
            )
        }
    }

    @Test
    fun `a near-straight bend with no real corner stays a single line`() {
        // The flip side of the sweep above: a bend this shallow (170
        // degrees interior, only ~10 degrees of turn) is negligible enough
        // that it should NOT be flagged as a discrete corner.
        val primitives = primitiveTypesOf(drivePrimitives(cornerShape(170.0)))
        assertEquals(listOf("line"), primitives)
    }

    /** Drives a whole synthetic stroke through Sensor -> PrimitiveLM, per the orchestrator's per-point loop. */
    private fun drivePrimitives(points: List<RawPoint>): List<CmpMessage> {
        val lm = PrimitiveLM(lmId = "primitive-0")
        val observations = StrokePreprocessor.preprocess(points, strokeIndex = 0)

        lm.preEpisode()
        val emitted = mutableListOf<CmpMessage>()
        for (observation in observations) {
            lm.matchingStep(listOf(sensor.step(observation)))
            drain(lm, emitted)
        }
        lm.postEpisode()
        drain(lm, emitted)
        return emitted
    }

    private fun drain(
        lm: PrimitiveLM,
        into: MutableList<CmpMessage>,
    ) {
        while (true) {
            into += lm.getOutput() ?: break
        }
    }

    /** Drives a whole character's worth of strokes through Sensor -> PrimitiveLM, per the orchestrator's per-character episode (§3.6). */
    private fun drivePrimitivesMultiStroke(strokes: List<List<RawPoint>>): List<CmpMessage> {
        val lm = PrimitiveLM(lmId = "primitive-0")
        lm.preEpisode() // once per character, not once per stroke
        val emitted = mutableListOf<CmpMessage>()
        strokes.forEachIndexed { strokeIndex, points ->
            val observations = StrokePreprocessor.preprocess(points, strokeIndex)
            for (observation in observations) {
                lm.matchingStep(listOf(sensor.step(observation)))
                drain(lm, emitted)
            }
        }
        lm.postEpisode()
        drain(lm, emitted)
        return emitted
    }

    private fun primitiveTypesOf(messages: List<CmpMessage>): List<String> =
        messages.map { (it.nonMorphologicalFeatures as PrimitiveFeatures).type.name.lowercase() }

    @Test
    fun `a straight line segments into a single line primitive`() {
        val line = List(40) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val primitives = drivePrimitives(line)
        assertEquals(listOf("line"), primitiveTypesOf(primitives))
    }

    @Test
    fun `an L shape segments into line, corner, line`() {
        // Straight down, then a sharp single-point corner, then straight right.
        val down = List(20) { i -> RawPoint(0f, i.toFloat()) }
        val right = List(20) { i -> RawPoint(i.toFloat(), 19f) }
        val lShape = down + right.drop(1)

        val primitives = drivePrimitives(lShape)

        assertEquals(listOf("line", "corner", "line"), primitiveTypesOf(primitives))
    }

    @Test
    fun `a straight line with realistic touch jitter still segments as a single line`() {
        // Simulates finger tremor: a perpendicular wobble superimposed on an
        // otherwise straight diagonal, large relative to the spacing between
        // points and oscillating often (unlike a real sustained curve). This
        // used to fool decide()'s line-consistency check -- comparing every
        // point against a single first-point reference, with no noise
        // cancellation -- into spuriously breaking one stroke into several
        // "line"/"arc" primitives.
        val jitteredLine =
            List(60) { i ->
                val t = i.toFloat()
                val wobble = 0.4f * sin(t * 0.4f)
                RawPoint(t + wobble, t - wobble)
            }
        val primitives = drivePrimitives(jitteredLine)
        assertEquals(listOf("line"), primitiveTypesOf(primitives))
    }

    @Test
    fun `a semicircle segments into a single arc primitive`() {
        val semicircle =
            List(40) { i ->
                val angle = PI * i / 39
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val primitives = drivePrimitives(semicircle)
        assertEquals(listOf("arc"), primitiveTypesOf(primitives))
    }

    @Test
    fun `a shallow, gently-curved stroke segments as one cohesive primitive, not a string of spurious lines`() {
        // A much gentler curve than the semicircle test above -- close to
        // the boundary between "line" and "arc" -- specifically targeting
        // the dead zone a whole-run tangent average used to open: a real,
        // sustained-but-shallow curve could drift far enough to break LINE
        // without its curvature ever reaching MIN_ARC_CURVATURE, producing
        // a spurious run of several "line" primitives with nothing (no
        // CORNER, no ARC) between them. Whether this particular shallowness
        // lands on the "line" or "arc" side of the threshold is a boundary
        // call this test doesn't care about -- what matters is that it's
        // ONE primitive, not several.
        val shallowArc =
            List(40) { i ->
                val angle = Math.toRadians(30.0) * i / 39
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val primitives = drivePrimitives(shallowArc)
        assertEquals(
            "expected one cohesive primitive but got ${primitiveTypesOf(primitives)}",
            1,
            primitives.size,
        )
    }

    @Test
    fun `state is stateless and experiment mode does not throw`() {
        val lm = PrimitiveLM(lmId = "primitive-0")
        assertEquals(Unit, lm.state())
        lm.setExperimentMode(ExperimentMode.TRAIN)
        lm.loadState(Unit)
    }

    @Test
    fun `voting is a no-op in v1`() {
        val lm = PrimitiveLM(lmId = "primitive-0")
        assertEquals(null, lm.sendOutVote())
        lm.receiveVotes(emptyList())
    }

    @Test
    fun `a stroke gap force-breaks a run even when tangent stays consistent`() {
        // Two collinear diagonal strokes with a pen-lift gap between them: nothing
        // about the tangent trips decide()'s Break, so without the strokeIndex
        // discontinuity guard these would wrongly collapse into a single line.
        val stroke0 = List(20) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val stroke1 = List(20) { i -> RawPoint(20f + i, 20f + i) }

        val primitives = drivePrimitivesMultiStroke(listOf(stroke0, stroke1))

        assertEquals(listOf("line", "line"), primitiveTypesOf(primitives))
        assertEquals(0, (primitives[0].nonMorphologicalFeatures as PrimitiveFeatures).strokeIndex)
        assertEquals(1, (primitives[1].nonMorphologicalFeatures as PrimitiveFeatures).strokeIndex)
    }

    @Test
    fun `previousExitAngle keeps tracking continuously across a stroke gap`() {
        // Same collinear strokes as above: since both lines share the same absolute
        // tangent, the second primitive's turn-from-previous-exit-angle should be
        // ~0 -- only true if previousExitAngle carried over the gap instead of being
        // reset to 0, which would instead report the line's raw ~45 degree angle.
        val stroke0 = List(20) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val stroke1 = List(20) { i -> RawPoint(20f + i, 20f + i) }

        val primitives = drivePrimitivesMultiStroke(listOf(stroke0, stroke1))

        val poseVectors = requireNotNull(primitives[1].getPoseVectors())
        val relativeAngle = atan2(poseVectors[0][1], poseVectors[0][0])
        assertTrue(
            "expected ~0 turn continuing the same direction across the gap, was $relativeAngle",
            abs(relativeAngle) < 0.1f,
        )
    }
}
