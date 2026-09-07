package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class PrimitiveSensorModuleTest {
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
    fun `corners at various interior angles segment as two lines, not a third corner type`() {
        // There's no dedicated CORNER type (see PrimitiveSensorModule's
        // class doc for why): a bend is just the boundary between two
        // LINE/ARC runs, so this should hold for a sharp right angle all
        // the way down to a fairly gentle bend.
        for (interiorAngle in listOf(90.0, 110.0, 130.0, 150.0)) {
            val shape = cornerShape(interiorAngle)
            val primitives = primitiveTypesOf(drivePrimitives(shape))
            assertEquals("interior angle $interiorAngle degrees", listOf("line", "line"), primitives)
        }
    }

    @Test
    fun `a near-straight bend with no real corner stays a single line`() {
        // A bend this shallow (170 degrees interior, only ~10 degrees of
        // turn) is negligible enough that it shouldn't split into two
        // primitives at all.
        val primitives = primitiveTypesOf(drivePrimitives(cornerShape(170.0)))
        assertEquals(listOf("line"), primitives)
    }

    @Test
    fun `the same corner shape produces the same primitive sequence whether drawn as one stroke or two`() {
        // The actual point of dropping CORNER as a distinct type: a bend
        // drawn as one continuous stroke and the identical shape drawn as
        // two separate strokes meeting at that same point must produce the
        // same graph -- otherwise GraphMatcher (which requires equal node
        // counts) could never match one against the other. Synthesizing a
        // corner only at "detected" stroke boundaries would still be order/
        // direction-dependent (which stroke connects to which, drawn in
        // what order); not having a corner type at all sidesteps that
        // entirely, since there's nothing left that only fires for one of
        // the two cases.
        val shape = cornerShape(120.0)
        val vertexIndex = 20 // cornerShape's leg length

        val continuous = primitiveTypesOf(drivePrimitives(shape))
        val twoStrokes =
            primitiveTypesOf(
                drivePrimitivesMultiStroke(
                    listOf(shape.subList(0, vertexIndex), shape.subList(vertexIndex, shape.size)),
                ),
            )

        assertEquals(continuous, twoStrokes)
        assertEquals(listOf("line", "line"), continuous)
    }

    /** Drives a whole synthetic stroke through Sensor -> PrimitiveSensorModule, per the orchestrator's per-point loop. */
    private fun drivePrimitives(points: List<RawPoint>): List<CmpMessage> {
        val primitiveSensor = PrimitiveSensorModule(sensorId = "primitive-0")
        val observations = StrokePreprocessor.preprocess(points, strokeIndex = 0)

        primitiveSensor.preEpisode()
        val emitted = mutableListOf<CmpMessage>()
        for (observation in observations) {
            val message = primitiveSensor.step(sensor.step(observation))
            if (message.passMessage) emitted += message
        }
        primitiveSensor.postEpisode()
        primitiveSensor.drainTrailingPrimitive()?.let { emitted += it }
        return emitted
    }

    /** Drives a whole character's worth of strokes through Sensor -> PrimitiveSensorModule, per the orchestrator's per-character episode (§3.6). */
    private fun drivePrimitivesMultiStroke(strokes: List<List<RawPoint>>): List<CmpMessage> {
        val primitiveSensor = PrimitiveSensorModule(sensorId = "primitive-0")
        primitiveSensor.preEpisode() // once per character, not once per stroke
        val emitted = mutableListOf<CmpMessage>()
        strokes.forEachIndexed { strokeIndex, points ->
            val observations = StrokePreprocessor.preprocess(points, strokeIndex)
            for (observation in observations) {
                val message = primitiveSensor.step(sensor.step(observation))
                if (message.passMessage) emitted += message
            }
        }
        primitiveSensor.postEpisode()
        primitiveSensor.drainTrailingPrimitive()?.let { emitted += it }
        return emitted
    }

    private fun measurementsOf(messages: List<CmpMessage>): List<PrimitiveMeasurement> =
        messages.map { (it.nonMorphologicalFeatures as PrimitiveFeatures).measurement }

    /** The measurement variant doubles as the primitive's type — see [PrimitiveMeasurement]'s class doc. */
    private fun primitiveTypesOf(messages: List<CmpMessage>): List<String> =
        measurementsOf(messages).map {
            when (it) {
                is PrimitiveMeasurement.Line -> "line"
                is PrimitiveMeasurement.Arc -> "arc"
            }
        }

    @Test
    fun `a straight line segments into a single line primitive`() {
        val line = List(40) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val primitives = drivePrimitives(line)
        assertEquals(listOf("line"), primitiveTypesOf(primitives))
    }

    @Test
    fun `a straight line reports its length as the end-to-end chord in normalized space`() {
        // normalize() scales so the farthest point from the centroid sits at
        // radius 1 -- for a straight line, that's both endpoints, so the
        // whole line's chord (this being one single run) is the full
        // diameter, 2.0, regardless of the line's original pixel length.
        val line = List(40) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val measurement = measurementsOf(drivePrimitives(line)).single() as PrimitiveMeasurement.Line
        assertEquals(2f, measurement.length, 0.05f)
    }

    @Test
    fun `a longer line reports a larger length than a shorter line drawn the same way`() {
        // Both lines get independently normalized to unit radius, so this
        // isn't about raw pixel length -- it's checking the two shapes (a
        // short leg vs. a long leg of a bend) are distinguishable at all,
        // which the length measurement now makes possible where a bare
        // type tag could not.
        val shortLeg = cornerShape(interiorAngleDegrees = 90.0, legLength = 20)
        val longLeg = cornerShape(interiorAngleDegrees = 90.0, legLength = 5)
        val shortLegFirstLength = (measurementsOf(drivePrimitives(shortLeg)).first() as PrimitiveMeasurement.Line).length
        val longLegFirstLength = (measurementsOf(drivePrimitives(longLeg)).first() as PrimitiveMeasurement.Line).length
        assertTrue(
            "expected the 20-point leg's relative length to exceed the 5-point leg's, " +
                "was $shortLegFirstLength vs $longLegFirstLength",
            shortLegFirstLength > longLegFirstLength,
        )
    }

    @Test
    fun `a semicircle reports a sweep angle close to PI`() {
        val semicircle =
            List(40) { i ->
                val angle = PI * i / 39
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val measurement = measurementsOf(drivePrimitives(semicircle)).single() as PrimitiveMeasurement.Arc
        assertEquals(PI.toFloat(), abs(measurement.sweepAngle), 0.1f)
    }

    @Test
    fun `a tight full loop reports a sweep angle close to a full turn`() {
        val loop =
            List(48) { i ->
                val angle = 2 * PI * i / 47
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val measurement = measurementsOf(drivePrimitives(loop)).single() as PrimitiveMeasurement.Arc
        assertEquals((2 * PI).toFloat(), abs(measurement.sweepAngle), 0.2f)
    }

    @Test
    fun `a shallow arc reports a larger circle radius than a semicircle sliced from the same size character`() {
        // A sweep angle alone doesn't capture size -- a shallow slice of a
        // huge circle and a tight semicircle can report similar-looking
        // shapes without a radius to tell them apart. Both shapes here get
        // independently normalized to the same unit bounding radius, so a
        // shallow arc's own underlying circle -- being much bigger than the
        // small slice of it that's visible -- should measure as having a
        // clearly larger radius than the semicircle's, whose circle roughly
        // matches its own bounding box.
        val semicircle =
            List(40) { i ->
                val angle = PI * i / 39
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val shallowArc =
            List(40) { i ->
                val angle = Math.toRadians(30.0) * i / 39
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val semicircleRadius = (measurementsOf(drivePrimitives(semicircle)).single() as PrimitiveMeasurement.Arc).radius
        val shallowArcRadius = (measurementsOf(drivePrimitives(shallowArc)).single() as PrimitiveMeasurement.Arc).radius
        assertTrue(
            "expected the shallow arc's radius ($shallowArcRadius) to exceed the semicircle's ($semicircleRadius)",
            shallowArcRadius > semicircleRadius,
        )
    }

    @Test
    fun `a straight line with realistic touch jitter still segments as a single line`() {
        // Simulates finger tremor: a perpendicular wobble superimposed on an
        // otherwise straight diagonal, large relative to the spacing between
        // points and oscillating often (unlike a real sustained curve). The
        // whole-window aspect-ratio test tolerates this because it's a
        // global fit over the candidate span (noise mostly cancels), not a
        // per-point running comparison.
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
    fun `a tight full loop segments into a single arc primitive, not several lines`() {
        // The regression test for switching the arc test from curvature
        // mean/std to a fitted-circle residual: a full 360-degree loop's
        // measured *curvature* at this resample density used to sit right
        // at the same threshold used to veto sharp corners (both scale with
        // how "tight" the turn is), fragmenting the loop into several
        // spurious lines. A circle-fit residual doesn't care how tight the
        // loop is -- a clean loop of any radius sits close to *some*
        // circle -- so this should hold regardless of loop size.
        val loop =
            List(48) { i ->
                val angle = 2 * PI * i / 47
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val primitives = drivePrimitives(loop)
        assertEquals(listOf("arc"), primitiveTypesOf(primitives))
    }

    @Test
    fun `a shallow, gently-curved stroke segments as one cohesive primitive, not a string of spurious lines`() {
        // A much gentler curve than the semicircle test above -- close to
        // the boundary between "line" and "arc". Whether this particular
        // shallowness lands on the "line" or "arc" side of the threshold is
        // a boundary call this test doesn't care about -- what matters is
        // that it's ONE primitive, not several.
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
    fun `a gentle S-curve sharing one stroke's resample budget doesn't fragment into spurious lines`() {
        // Regression test for a real hand-drawn bug report: a smooth S drawn
        // as one continuous stroke was fragmenting into ~5 meaningless LINE
        // segments. Root cause was in StrokePreprocessor.tangentsAndCurvatures
        // (see its class doc): curvature used to be a bare per-resampled-step
        // turning angle, which is NOT invariant to how many points a given
        // curve gets out of the fixed DEFAULT_RESAMPLE_COUNT-per-stroke
        // budget -- an S-curve's two lobes only get half that budget each
        // (sharing it with the rest of the S), so the same true curvature
        // measured roughly double what a single semicircle filling the
        // whole budget alone would show, spuriously tripping
        // PrimitiveSensorModule's sharp-corner veto (MAX_LOCAL_TURN) at
        // many points along an otherwise perfectly smooth curve. Now that
        // curvature is real, density-invariant differential curvature
        // (radians per unit length), a gentle S like this should segment
        // into a small, cohesive handful of primitives, not five-plus
        // spurious fragments.
        val amplitude = 60f
        val length = 600f
        val sCurve =
            List(150) { i ->
                val t = i / 149f
                RawPoint(amplitude * sin(2 * PI * t).toFloat(), length * t)
            }
        val primitives = drivePrimitives(sCurve)
        assertTrue(
            "expected at most 2 cohesive primitives but got ${primitiveTypesOf(primitives)}",
            primitives.size <= 2,
        )
    }

    @Test
    fun `a stroke gap force-breaks a run even when tangent stays consistent`() {
        // Two collinear diagonal strokes with a pen-lift gap between them: nothing
        // about the tangent trips the fit test, so without the strokeIndex
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
