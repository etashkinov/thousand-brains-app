package com.eta.tbp.lib.util

import com.eta.tbp.lib.memory.Location
import kotlin.math.abs
import kotlin.math.sqrt

private val PI_F = kotlin.math.PI.toFloat()

/**
 * The angle/position alignment score shared by [com.eta.tbp.lib.memory.GraphMatcher]
 * (Tier 2, sparse character nodes) and [com.eta.tbp.lib.lm.PrimitiveGraphLM]
 * (Tier 1, dense primitive-window points) — both re-baseline each side's
 * angle to its own first element before comparing, then average an
 * angle-error term with a position-error term. [gate] is a hard,
 * short-circuiting precondition (0f immediately if it fails for any
 * aligned pair) — for Tier 2, "must be the same taught label"; Tier 1 has
 * none. [extraTerm], if supplied, is folded in as a genuine third term
 * (Tier 2's size score); when null the result is the plain 2-term
 * angle+position average Tier 1 uses today — the presence/absence of a
 * term changes which formula runs, never a neutral default silently
 * changing the divisor (an earlier draft of this unification always
 * folded in a neutral third term, which would have silently shifted Tier
 * 1's score by a few percent — see IMPLEMENTATION_PLAN.md §7).
 */
fun <T> alignmentScore(
    window: List<T>,
    target: List<T>,
    angleOf: (T) -> Float,
    positionOf: (T) -> FloatArray,
    gate: (T, T) -> Boolean = { _, _ -> true },
    maxPositionError: Float = 1f,
    extraTerm: ((T, T) -> Float)? = null,
): Float {
    for (i in window.indices) {
        if (!gate(window[i], target[i])) return 0f
    }

    val windowBaseline = angleOf(window.first())
    val targetBaseline = angleOf(target.first())

    var angleError = 0f
    var positionError = 0f
    var extraSum = 0f
    for (i in window.indices) {
        val windowAngle = angleDifference(angleOf(window[i]), windowBaseline)
        val targetAngle = angleDifference(angleOf(target[i]), targetBaseline)
        angleError += abs(angleDifference(windowAngle, targetAngle))
        positionError += distance(positionOf(window[i]), positionOf(target[i]))
        if (extraTerm != null) extraSum += extraTerm(window[i], target[i])
    }

    val n = window.size
    val angleScore = (1f - (angleError / n) / PI_F).coerceIn(0f, 1f)
    val positionScore = (1f - (positionError / n) / maxPositionError).coerceIn(0f, 1f)
    return if (extraTerm != null) {
        (angleScore + positionScore + extraSum / n) / 3f
    } else {
        (angleScore + positionScore) / 2f
    }
}

private fun distance(
    a: FloatArray,
    b: FloatArray,
): Float {
    val dx = a[0] - b[0]
    val dy = a[1] - b[1]
    return sqrt(dx * dx + dy * dy)
}
