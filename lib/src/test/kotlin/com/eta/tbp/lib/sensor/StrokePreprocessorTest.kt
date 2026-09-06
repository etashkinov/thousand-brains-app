package com.eta.tbp.lib.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class StrokePreprocessorTest {
    private val epsilon = 1e-3f

    // --- resampleByArcLength ---

    @Test
    fun `empty input resamples to empty output`() {
        assertEquals(emptyList<RawPoint>(), StrokePreprocessor.resampleByArcLength(emptyList(), 10))
    }

    @Test
    fun `single point repeats to target count`() {
        val result = StrokePreprocessor.resampleByArcLength(listOf(RawPoint(3f, 4f)), 5)
        assertEquals(List(5) { RawPoint(3f, 4f) }, result)
    }

    @Test
    fun `all-coincident points repeat to target count without dividing by zero`() {
        val points = List(10) { RawPoint(1f, 1f) }
        val result = StrokePreprocessor.resampleByArcLength(points, 6)
        assertEquals(List(6) { RawPoint(1f, 1f) }, result)
    }

    @Test
    fun `resampling a straight line preserves endpoints and spaces points evenly`() {
        val line = List(20) { i -> RawPoint(i.toFloat(), 0f) }
        val resampled = StrokePreprocessor.resampleByArcLength(line, 10)

        assertPointsClose(RawPoint(0f, 0f), resampled.first())
        assertPointsClose(RawPoint(19f, 0f), resampled.last())

        val expectedSpacing = 19f / 9f
        for (i in 0 until resampled.size - 1) {
            val spacing = (resampled[i + 1] - resampled[i]).length()
            assertTrue(kotlin.math.abs(spacing - expectedSpacing) < epsilon)
        }
    }

    @Test
    fun `dense and sparse sampling of the same path resample to the same points`() {
        // Same start-to-end line, captured at very different point densities
        // (simulating slow drawing = many samples vs. fast drawing = few).
        fun pointOnLine(t: Float) = RawPoint(10f * t, 10f * t)
        val dense = List(200) { i -> pointOnLine(i / 199f) }
        val sparse = List(4) { i -> pointOnLine(i / 3f) }

        val resampledDense = StrokePreprocessor.resampleByArcLength(dense, 32)
        val resampledSparse = StrokePreprocessor.resampleByArcLength(sparse, 32)

        resampledDense.zip(resampledSparse).forEach { (a, b) -> assertPointsClose(a, b) }
    }

    // --- smooth ---

    @Test
    fun `smoothing leaves short inputs unchanged`() {
        val points = listOf(RawPoint(0f, 0f), RawPoint(1f, 1f))
        assertEquals(points, StrokePreprocessor.smooth(points))
    }

    @Test
    fun `smoothing a perfectly straight line leaves interior points exact and barely shifts the endpoints`() {
        val line = List(20) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val smoothed = StrokePreprocessor.smooth(line)

        // An average of collinear, evenly-spaced points is the same point,
        // so every interior point is exact.
        for (i in 1 until line.size - 1) {
            assertPointsClose(line[i], smoothed[i])
        }
        // The two endpoints get a real (necessarily one-sided) average with
        // their single neighbor too -- see smooth()'s doc on why -- so they
        // shift slightly along the line, but never by more than one point's
        // worth of spacing.
        assertTrue((smoothed.first() - line.first()).length() <= 1.01f)
        assertTrue((smoothed.last() - line.last()).length() <= 1.01f)
    }

    @Test
    fun `smoothing damps perpendicular jitter on an otherwise straight line`() {
        // A wobble large relative to the point spacing -- exactly the kind
        // of noise real touch input has that used to fool PrimitiveLM's
        // line-consistency check (see PrimitiveLMTest's jitter case).
        val jittered =
            List(30) { i ->
                val t = i.toFloat()
                val wobble = 0.6f * sin(t * 1.3f)
                RawPoint(t + wobble, t - wobble)
            }
        val ideal = List(30) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val smoothed = StrokePreprocessor.smooth(jittered)

        fun totalDeviation(points: List<RawPoint>) = points.zip(ideal).sumOf { (p, i) -> (p - i).length().toDouble() }
        assertTrue(
            "expected smoothing to reduce deviation from the ideal line",
            totalDeviation(smoothed) < totalDeviation(jittered),
        )
    }

    // --- normalize ---

    @Test
    fun `normalize centers on centroid and scales farthest point to unit radius`() {
        val square = listOf(RawPoint(0f, 0f), RawPoint(2f, 0f), RawPoint(2f, 2f), RawPoint(0f, 2f))
        val normalized = StrokePreprocessor.normalize(square)

        val centroid = normalized.reduce { a, b -> a + b } * (1f / normalized.size)
        assertPointsClose(RawPoint(0f, 0f), centroid)

        val maxRadius = normalized.maxOf { it.length() }
        assertTrue(kotlin.math.abs(maxRadius - 1f) < epsilon)
    }

    @Test
    fun `normalize does not divide by zero for a dot`() {
        val dot = List(5) { RawPoint(7f, -3f) }
        val normalized = StrokePreprocessor.normalize(dot)
        normalized.forEach { assertPointsClose(RawPoint(0f, 0f), it) }
    }

    @Test
    fun `same shape at different scales normalizes to near-identical points`() {
        val shape = listOf(RawPoint(0f, 0f), RawPoint(4f, 0f), RawPoint(4f, 4f), RawPoint(2f, 6f))
        val scaledUp = shape.map { it * 3f }

        val a = StrokePreprocessor.normalize(StrokePreprocessor.resampleByArcLength(shape, 32))
        val b = StrokePreprocessor.normalize(StrokePreprocessor.resampleByArcLength(scaledUp, 32))

        a.zip(b).forEach { (p1, p2) -> assertPointsClose(p1, p2) }
    }

    // --- tangentsAndCurvatures ---

    @Test
    fun `tangent is near zero curvature along a straight horizontal line`() {
        val line = List(20) { i -> RawPoint(i.toFloat(), 0f) }
        val (_, curvatures) = StrokePreprocessor.tangentsAndCurvatures(line).unzip()
        curvatures.forEach { assertTrue(kotlin.math.abs(it) < epsilon) }
    }

    @Test
    fun `curvature has a consistent sign around a circle`() {
        val circle =
            List(40) { i ->
                val angle = 2 * PI * i / 40
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val (_, curvatures) = StrokePreprocessor.tangentsAndCurvatures(circle).unzip()
        val interior = curvatures.subList(1, curvatures.size - 1)
        assertTrue(interior.all { it > 0f } || interior.all { it < 0f })
    }

    // --- preprocess (end to end) ---

    @Test
    fun `preprocess produces target count observations with sequential order`() {
        val line = List(10) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val observations = StrokePreprocessor.preprocess(line, strokeIndex = 2, targetCount = 16)

        assertEquals(16, observations.size)
        observations.forEachIndexed { index, observation ->
            assertEquals(index, observation.orderInStroke)
            assertEquals(2, observation.strokeIndex)
        }
    }

    private fun assertPointsClose(
        expected: RawPoint,
        actual: RawPoint,
    ) {
        assertTrue(
            "expected $expected but was $actual",
            (expected - actual).length() < epsilon,
        )
    }
}
