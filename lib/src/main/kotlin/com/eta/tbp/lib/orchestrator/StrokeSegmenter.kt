package com.eta.tbp.lib.orchestrator

/**
 * Finds the highest-scoring way to partition a sequence of [pointCount]
 * points into contiguous windows, given a caller-supplied [WindowScore] for
 * any candidate window — a classic Viterbi-style optimal segmentation, the
 * same shape as segmenting text into dictionary words scored by a language
 * model, applied here to segmenting a stroke into primitives scored by how
 * well each candidate window matches a taught shape.
 *
 * This replaces the original, since-deleted `PrimitiveSensorModule`'s local,
 * per-point hard-threshold segmentation — a different class from the
 * current [com.eta.tbp.lib.sensor.PrimitiveSensorModule], which now calls
 * into this object instead (see IMPLEMENTATION_PLAN.md for the full history
 * of why the old approach kept failing on real handwriting): instead of a
 * single local yes/no decision at every point, the *whole* stroke's
 * segmentation is chosen at once to maximize total score. A wrong
 * segmentation that ends up matching nothing scores no better than a
 * "correct" one that also matches nothing — both correctly end up
 * unrecognized — so no single local decision has to be independently
 * perfect.
 *
 * Deliberately knows nothing about points, shapes, or matching — [segment]
 * takes only a point *count* and a score callback, so the algorithm itself
 * is fully unit-testable against synthetic score functions, independent of
 * any real geometry or learned templates.
 */
object StrokeSegmenter {
    /** One chosen window in a partition — points `[startIndex, endIndex)`. */
    data class Window(
        val startIndex: Int,
        val endIndex: Int,
    )

    /** Score for the candidate window `[startIndex, endIndex)` — higher is better. Called only for windows [segment] considers valid. */
    fun interface WindowScore {
        fun of(
            startIndex: Int,
            endIndex: Int,
        ): Float
    }

    /**
     * @param pointCount total points to partition; windows are chosen over `[0, pointCount)`.
     * @param minWindowLength a window shorter than this is only used when
     *   forced — at the very start or end of the stroke — so a very short
     *   stroke, or a stroke whose length doesn't evenly divide into
     *   [minWindowLength]-sized pieces, still produces *some* segmentation
     *   rather than none.
     * @param maxWindowLength a hard cap on any window's length, always enforced.
     * @param segmentPenalty subtracted once per chosen window, regardless
     *   of its length. Without this, the optimal partition always prefers
     *   more, shorter windows whenever a smaller window can score even
     *   slightly less than a larger one covering the same span — summing
     *   more similar scores beats summing fewer — which is exactly the
     *   "many spurious short primitives" failure this design exists to
     *   avoid. This is the algorithm's one tunable knob.
     * @param windowScore evidence (or a penalty) for any candidate window;
     *   see [WindowScore].
     * @return the winning partition, in order, covering `[0, pointCount)`
     *   exactly with no gaps or overlaps. Empty only when [pointCount] is 0.
     */
    fun segment(
        pointCount: Int,
        minWindowLength: Int,
        maxWindowLength: Int,
        segmentPenalty: Float,
        windowScore: WindowScore,
    ): List<Window> {
        require(pointCount >= 0) { "pointCount must be non-negative, was $pointCount" }
        require(minWindowLength >= 1) { "minWindowLength must be at least 1, was $minWindowLength" }
        require(maxWindowLength >= minWindowLength) {
            "maxWindowLength ($maxWindowLength) must be at least minWindowLength ($minWindowLength)"
        }
        if (pointCount == 0) return emptyList()

        // bestScore[i] is the best total score of any valid partition of
        // [0, i); bestPrev[i] is the start of the last window in that
        // partition, for backtracking. Unreachable prefixes stay
        // NEGATIVE_INFINITY and are never selected as a predecessor.
        val bestScore = FloatArray(pointCount + 1) { Float.NEGATIVE_INFINITY }
        val bestPrev = IntArray(pointCount + 1) { -1 }
        bestScore[0] = 0f

        for (end in 1..pointCount) {
            for (start in maxOf(0, end - maxWindowLength) until end) {
                if (bestScore[start] == Float.NEGATIVE_INFINITY) continue
                if (!isAllowedWindow(start, end, pointCount, minWindowLength)) continue

                val candidateScore = bestScore[start] + windowScore.of(start, end) - segmentPenalty
                if (candidateScore > bestScore[end]) {
                    bestScore[end] = candidateScore
                    bestPrev[end] = start
                }
            }
        }

        check(bestScore[pointCount] != Float.NEGATIVE_INFINITY) {
            "no valid segmentation found for pointCount=$pointCount, minWindowLength=$minWindowLength, " +
                "maxWindowLength=$maxWindowLength — this should be unreachable, see isAllowedWindow"
        }

        val windows = mutableListOf<Window>()
        var end = pointCount
        while (end > 0) {
            val start = bestPrev[end]
            windows.add(Window(start, end))
            end = start
        }
        windows.reverse()
        return windows
    }

    /**
     * A window shorter than [minWindowLength] is only allowed at the very
     * start ([startIndex] == 0) or very end ([endIndex] == [pointCount]) of
     * the whole stroke — never in the interior. This is what guarantees
     * [segment] always finds *some* segmentation (walking forward from 0
     * and closing out at [pointCount] is always possible) without letting
     * short windows appear anywhere they're not structurally forced.
     */
    private fun isAllowedWindow(
        startIndex: Int,
        endIndex: Int,
        pointCount: Int,
        minWindowLength: Int,
    ): Boolean {
        val length = endIndex - startIndex
        return length >= minWindowLength || startIndex == 0 || endIndex == pointCount
    }
}
