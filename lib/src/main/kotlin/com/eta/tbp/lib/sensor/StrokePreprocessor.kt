package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.util.wrapAngle
import kotlin.math.atan2

/**
 * Turns a raw, variable-length, variable-speed stroke into a fixed-length,
 * speed- and scale-invariant sequence of [RawTouchObservation]s.
 *
 * The three stages (resample, normalize, tangent/curvature) are exposed
 * separately so each is independently unit-testable; [preprocess] is the
 * single entry point that chains them for real use.
 */
object StrokePreprocessor {
    const val DEFAULT_RESAMPLE_COUNT = 48

    private const val LENGTH_EPSILON = 1e-4f

    /**
     * Walks the polyline's cumulative arc length and linearly interpolates
     * [targetCount] evenly-spaced points along it. The first and last input
     * points are preserved exactly.
     *
     * Degenerate inputs never throw: an empty list resamples to an empty
     * list, and a single point (or a stroke where every point coincides)
     * resamples to that point repeated [targetCount] times.
     */
    fun resampleByArcLength(
        points: List<RawPoint>,
        targetCount: Int,
    ): List<RawPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(targetCount) { points[0] }

        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + (points[i] - points[i - 1]).length()
        }
        val totalLength = cumulative.last()
        if (totalLength < LENGTH_EPSILON) return List(targetCount) { points[0] }

        return List(targetCount) { k ->
            val targetDistance = totalLength * k / (targetCount - 1)
            interpolateAt(points, cumulative, targetDistance)
        }
    }

    private fun interpolateAt(
        points: List<RawPoint>,
        cumulative: FloatArray,
        targetDistance: Float,
    ): RawPoint {
        var segmentEnd = 1
        while (segmentEnd < cumulative.size - 1 && cumulative[segmentEnd] < targetDistance) {
            segmentEnd++
        }
        val segmentStart = segmentEnd - 1
        val segmentLength = cumulative[segmentEnd] - cumulative[segmentStart]
        val t = if (segmentLength < LENGTH_EPSILON) 0f else (targetDistance - cumulative[segmentStart]) / segmentLength
        return points[segmentStart] + (points[segmentEnd] - points[segmentStart]) * t
    }

    /**
     * Translates by subtracting the centroid, then scales so the farthest
     * point from the centroid sits at radius 1.0. Skips the scale step
     * (translate only) when the bounding radius is ~0 — a dot/period
     * stroke has no meaningful scale to normalize to.
     */
    fun normalize(points: List<RawPoint>): List<RawPoint> {
        if (points.isEmpty()) return points
        val centroid = points.reduce { a, b -> a + b } * (1f / points.size)
        val translated = points.map { it - centroid }
        val boundingRadius = translated.maxOf { it.length() }
        if (boundingRadius < LENGTH_EPSILON) return translated
        return translated.map { it * (1f / boundingRadius) }
    }

    /**
     * Tangent angle per point via central difference (direction to the next
     * point, or from the previous point at the ends). Curvature is the
     * wrapped turning-angle delta between consecutive segments — a turning
     * angle per resampled step, not strict 1/radius curvature, which is
     * sufficient for Phase 2's tangent-stability segmentation. A stroke's
     * first/last point has no second neighbor to center a difference on, so
     * its curvature is clamped to its nearest interior neighbor's rather
     * than forced to 0 — otherwise every stroke would report an artificial
     * "flattening out" right at its endpoints, which Phase 2's segmenter
     * would mistake for the start of a straight line. Both are 0 when there
     * are too few points to define a curvature at all.
     */
    fun tangentsAndCurvatures(points: List<RawPoint>): List<Pair<Float, Float>> {
        if (points.size < 2) return points.map { 0f to 0f }

        val tangents =
            List(points.size) { i ->
                val direction =
                    when (i) {
                        0 -> points[1] - points[0]
                        points.size - 1 -> points[i] - points[i - 1]
                        else -> points[i + 1] - points[i - 1]
                    }
                atan2(direction.y, direction.x)
            }

        if (points.size < 3) return tangents.map { it to 0f }

        val curvatures = MutableList(points.size) { 0f }
        for (i in 1 until points.size - 1) {
            curvatures[i] = wrapAngle(tangents[i + 1] - tangents[i - 1])
        }
        curvatures[0] = curvatures[1]
        curvatures[points.size - 1] = curvatures[points.size - 2]

        return tangents.zip(curvatures)
    }

    /** Chains resample -> normalize -> tangent/curvature into observations. */
    fun preprocess(
        points: List<RawPoint>,
        strokeIndex: Int,
        targetCount: Int = DEFAULT_RESAMPLE_COUNT,
    ): List<RawTouchObservation> {
        val resampled = normalize(resampleByArcLength(points, targetCount))
        val tangentsAndCurvatures = tangentsAndCurvatures(resampled)
        return resampled.mapIndexed { index, point ->
            val (tangentAngle, curvature) = tangentsAndCurvatures[index]
            RawTouchObservation(
                position = point.toFloatArray(),
                tangentAngle = tangentAngle,
                curvature = curvature,
                strokeIndex = strokeIndex,
                orderInStroke = index,
            )
        }
    }
}
