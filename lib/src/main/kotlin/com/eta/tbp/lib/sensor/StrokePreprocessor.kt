package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.util.wrapAngle
import kotlin.math.atan2

/**
 * Turns a raw, variable-length, variable-speed stroke into a fixed-length,
 * speed- and scale-invariant sequence of [RawTouchObservation]s.
 *
 * The four stages (resample, smooth, normalize, tangent/curvature) are
 * exposed separately so each is independently unit-testable; [preprocess]
 * is the single entry point that chains them for real use.
 */
object StrokePreprocessor {
    const val DEFAULT_RESAMPLE_COUNT = 48
    const val DEFAULT_SMOOTHING_WINDOW_RADIUS = 1

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
     * Centered moving-average over [windowRadius] neighbors on each side,
     * shrinking symmetrically near the ends — `min(windowRadius, i,
     * size-1-i)` — rather than clamping to a lopsided window there, which
     * would pull the first/last few points substantially toward the
     * interior of the stroke (the average of `points[0..2*radius]` is not
     * `points[0]`), inventing a spurious kink right where a stroke actually
     * starts/ends.
     *
     * The two true endpoints still get a (necessarily one-sided) average
     * with their single immediate neighbor rather than being left
     * completely unsmoothed: [com.eta.tbp.lib.sensor.StrokePreprocessor.tangentsAndCurvatures]
     * estimates the tangent at the very last point from only that point and
     * its immediate neighbor (a shorter, noisier baseline than the central
     * difference interior points get), so leaving it fully raw would let
     * jitter concentrated at the endpoints — exactly where a real finger
     * touch-down/lift-off tends to be noisiest — flow straight through
     * unfiltered and spuriously break the run right at the tail.
     *
     * Damps the position jitter inherent to real touch input *before*
     * tangent/curvature estimation amplifies it: a lateral wobble of only a
     * couple pixels between closely-spaced resampled points can otherwise
     * read as a large angular deviation (`atan2` of a small perpendicular
     * offset over a short forward step swings wildly), spuriously tripping
     * thresholds tuned for genuinely sharp turns. Smoothing mostly cancels
     * random per-point jitter while barely touching a real, sustained curve
     * or corner, since those shift a whole neighborhood in the same
     * direction rather than randomly.
     */
    fun smooth(
        points: List<RawPoint>,
        windowRadius: Int = DEFAULT_SMOOTHING_WINDOW_RADIUS,
    ): List<RawPoint> {
        if (points.size <= 2 || windowRadius <= 0) return points
        val lastIndex = points.size - 1
        return List(points.size) { i ->
            val start: Int
            val end: Int
            when (i) {
                0 -> {
                    start = 0
                    end = minOf(windowRadius, lastIndex)
                }

                lastIndex -> {
                    start = maxOf(0, lastIndex - windowRadius)
                    end = lastIndex
                }

                else -> {
                    val radius = minOf(windowRadius, i, lastIndex - i)
                    start = i - radius
                    end = i + radius
                }
            }
            var sum = RawPoint(0f, 0f)
            for (j in start..end) sum += points[j]
            sum * (1f / (end - start + 1))
        }
    }

    /**
     * Tangent angle per point via central difference (direction to the next
     * point, or from the previous point at the ends). Curvature is the
     * wrapped turning-angle delta between consecutive segments divided by
     * the arc length spanned by those segments — real differential-geometry
     * curvature (κ = dθ/ds, radians per unit length; ≈ 1/radius for a
     * circle), not a bare per-step turning angle.
     *
     * This division matters: an earlier version returned the bare angle
     * delta, undocumented as being anything other than "sufficient for
     * Phase 2's tangent-stability segmentation" at the time. That measure
     * is NOT invariant to how densely a curve happens to be resampled — the
     * same true semicircle resampled to 24 points (because it's sharing a
     * fixed 48-point-per-stroke budget with a second primitive in the same
     * stroke) reports roughly double the per-step turning angle of the same
     * semicircle resampled to 48 points alone, purely from having half as
     * many steps to cover the same 180° turn. The original, since-deleted
     * `PrimitiveSensorModule`'s sharp-corner veto (`MAX_LOCAL_TURN`) —
     * a different class from the current [PrimitiveSensorModule] — compared
     * this value against a fixed threshold meant to catch genuine corners,
     * not resample density —
     * dividing by arc length is what makes a smooth arc read as smoothly
     * curved regardless of how many strokes/primitives share its character's
     * resample budget, instead of spuriously tripping the corner veto and
     * fragmenting into meaningless line segments (see IMPLEMENTATION_PLAN.md
     * §7 for the concrete real-drawing repro that surfaced this).
     *
     * A stroke's first/last point has no second neighbor to center a
     * difference on, so its curvature is clamped to its nearest interior
     * neighbor's rather than forced to 0 — otherwise every stroke would
     * report an artificial "flattening out" right at its endpoints, which
     * the segmenter would mistake for the start of a straight line. Both
     * are 0 when there are too few points, or too little arc length between
     * them, to define a curvature at all.
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
            val deltaTheta = wrapAngle(tangents[i + 1] - tangents[i - 1])
            val arcLength = (points[i] - points[i - 1]).length() + (points[i + 1] - points[i]).length()
            curvatures[i] = if (arcLength > LENGTH_EPSILON) deltaTheta / arcLength else 0f
        }
        curvatures[0] = curvatures[1]
        curvatures[points.size - 1] = curvatures[points.size - 2]

        return tangents.zip(curvatures)
    }

    /** Chains resample -> smooth -> normalize -> tangent/curvature into observations. */
    fun preprocess(
        points: List<RawPoint>,
        strokeIndex: Int,
        targetCount: Int = DEFAULT_RESAMPLE_COUNT,
    ): List<RawTouchObservation> {
        val resampled = normalize(smooth(resampleByArcLength(points, targetCount)))
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
