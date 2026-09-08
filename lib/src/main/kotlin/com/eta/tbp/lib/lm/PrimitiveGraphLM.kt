package com.eta.tbp.lib.lm

import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.RawTouchObservation
import com.eta.tbp.lib.sensor.StrokePreprocessor
import com.eta.tbp.lib.util.AlignmentSearch
import com.eta.tbp.lib.util.alignmentScore
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tier 1: a taught, evidence-matched primitive recognizer — replaces
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule]'s hand-coded line/arc
 * geometric fit tests, which were recalibrated five times chasing real
 * hand-drawn failures and never converged (see IMPLEMENTATION_PLAN.md §7).
 * The user teaches primitive shapes — a line, an arc, a loop, whatever
 * labels naturally arise — by drawing an example and labeling it, exactly
 * the way [CharacterGraphLM] is taught characters. There is no fixed,
 * hand-designed shape vocabulary anywhere in this class.
 *
 * Given a candidate window (a short slice of a stroke, *proposed* by
 * [com.eta.tbp.lib.orchestrator.StrokeSegmenter]'s global search rather
 * than decided locally), [evaluate] resamples it to a small, fixed point
 * count and matches it — via the same order/direction-tolerant alignment
 * search [com.eta.tbp.lib.memory.GraphMatcher] uses at the character level
 * ([AlignmentSearch]) — against every taught template, reporting evidence
 * per label. It never decides "this window is definitely a line" on its
 * own; that decision belongs to whichever full-stroke segmentation scores
 * best overall, mirroring Monty's own "maintain multiple hypotheses, let
 * evidence decide" principle instead of an early, irreversible local
 * commitment.
 *
 * Each window is resampled, *locally* normalized (its own centroid and
 * bounding radius, independent of whatever normalization the character
 * this window is part of used), and rotated so its own first tangent
 * points along a fixed axis — so shape recognition here is deliberately
 * scale- *and* orientation-invariant: a taught "line" should match a short
 * line and a long one, drawn at any angle, equally well. Rotation
 * invariance matters as much as scale invariance does and is easy to miss:
 * [com.eta.tbp.lib.util.angleDifference]-based re-baselining alone only
 * makes the *tangent-angle* comparison orientation-independent — a window's
 * raw positions are still expressed in whatever absolute orientation the
 * stroke happened to be drawn in unless they're rotated too, so a "line"
 * taught pointing one way would otherwise barely match a fresh line
 * pointing a different way on *position* alone, even though the shape is
 * identical (see IMPLEMENTATION_PLAN.md §7 for the concrete failure this
 * caused: a taught diagonal line and a fresh vertical line, geometrically
 * the same primitive, scored well under what a same-shape match should).
 * Absolute orientation is exactly the information
 * [com.eta.tbp.lib.memory.GraphNode.absoluteAngle] preserves at the
 * character level instead — recognizing *which* shape a window is and
 * recognizing *how it's oriented* are different tiers' jobs. Size is
 * reported separately too, as
 * [com.eta.tbp.lib.sensor.PrimitiveMeasurement.extent] computed from the
 * *winning* window's own un-normalized chord length, once segmentation
 * search has chosen it — not this class's concern.
 *
 * Deliberately not a [LearningModule]: there's no fixed per-observation
 * `CmpMessage` stream feeding this tier the way a real SM feeds an LM —
 * [com.eta.tbp.lib.orchestrator.MontyOrchestrator] queries it directly with
 * candidate windows during segmentation search, the same "direct
 * introspection query" spirit as [CharacterGraphLM.evidenceSnapshot]. It
 * still mirrors [CharacterGraphLM]'s teach-by-drawing contract in shape,
 * just at a different granularity and — deliberately — not in exact
 * mechanism: [CharacterGraphLM.teach] labels whatever was *last completed*,
 * safe there because [CharacterGraphLM.matchingStep] is only ever called
 * once per real, already-decided primitive. [evaluate] here gets called
 * many times per replay for purely speculative candidate windows the
 * segmentation search is still trying out — "last evaluated" would be
 * whatever candidate the search happened to check most recently, not what
 * the user actually just drew to teach — so [teach] takes the points
 * directly instead of relying on any such side effect. [evaluate] is a
 * pure function of its input.
 *
 * Unlike [CharacterGraphLM], this keeps every taught example as its own
 * template variant rather than merging near-duplicates: at the handful of
 * primitive examples a curriculum like this actually needs, merge-vs-spawn
 * bookkeeping isn't worth the added complexity yet.
 */
class PrimitiveGraphLM {
    private val templates = mutableMapOf<String, MutableList<List<RawTouchObservation>>>()

    /**
     * Evidence per taught label for how well [points] (a candidate
     * window's raw positions, in whatever coordinate space the caller
     * already has them — local re-normalization below makes that
     * irrelevant) matches each taught primitive template. Empty if
     * nothing's been taught yet. The best label/score pair here is exactly
     * what [com.eta.tbp.lib.orchestrator.StrokeSegmenter] uses to score a
     * candidate window during segmentation search.
     */
    fun evaluate(points: List<RawPoint>): Map<String, Float> {
        val window = resample(points)
        return templates.keys.associateWith { label ->
            templates.getValue(label).maxOf { template -> matchScore(template, window) }
        }
    }

    /** Stores [points] (the example just drawn) as a new template variant for [label]. */
    fun teach(
        label: String,
        points: List<RawPoint>,
    ) {
        templates.getOrPut(label) { mutableListOf() }.add(resample(points))
    }

    /** Every label taught so far — used to gate character teaching behind "shapes before characters" (see IMPLEMENTATION_PLAN.md). */
    fun allLabels(): Set<String> = templates.keys

    /**
     * Resamples/normalizes via [StrokePreprocessor.preprocess], then
     * rotates every point (position *and* tangent angle, consistently) so
     * the window's own first tangent points along the positive x-axis —
     * see class doc for why this orientation-canonicalization step is
     * needed in addition to translate+scale normalization.
     */
    private fun resample(points: List<RawPoint>): List<RawTouchObservation> {
        val observations = StrokePreprocessor.preprocess(points, strokeIndex = 0, targetCount = WINDOW_NODE_COUNT)
        val rotation = -observations.first().tangentAngle
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        return observations.map { observation ->
            val x = observation.position[0]
            val y = observation.position[1]
            observation.copy(
                position = floatArrayOf(x * cosR - y * sinR, x * sinR + y * cosR),
                tangentAngle = observation.tangentAngle + rotation,
            )
        }
    }

    private fun matchScore(
        stored: List<RawTouchObservation>,
        target: List<RawTouchObservation>,
    ): Float = AlignmentSearch.bestAlignment(stored, target, ::alignmentScore).score

    /**
     * Re-baselines each side's tangent angle to its own first node before
     * comparing — same trick as [com.eta.tbp.lib.memory.GraphMatcher], and
     * for the same reason: a tangent angle is only meaningful relative to
     * *some* reference, and direction reversal falls out for free once
     * both sides are re-baselined independently. No label gate and no size
     * term here (unlike `GraphMatcher.alignmentScore`) — a window being
     * compared against one specific template already fixes which label is
     * being tested, and every window is locally scale-normalized before
     * this runs, so size isn't a meaningful axis at this granularity.
     */
    private fun alignmentScore(
        window: List<RawTouchObservation>,
        target: List<RawTouchObservation>,
    ): Float =
        alignmentScore(
            window,
            target,
            angleOf = { it.tangentAngle },
            positionOf = { it.position },
            maxPositionError = MAX_POSITION_ERROR,
        )

    private companion object {
        /** Fixed point count every window (taught or candidate) is resampled to, so any two windows are directly comparable. */
        const val WINDOW_NODE_COUNT = 12

        const val MAX_POSITION_ERROR = 1f // normalized-space units (a window's own local normalization scales to unit radius)
    }
}
