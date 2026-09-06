package com.eta.tbp.lib.util

private const val PI_F = kotlin.math.PI.toFloat()
private const val TWO_PI_F = 2f * PI_F

/** Wraps an angle in radians to (-PI, PI]. */
fun wrapAngle(angle: Float): Float {
    var wrapped = angle
    while (wrapped > PI_F) wrapped -= TWO_PI_F
    while (wrapped < -PI_F) wrapped += TWO_PI_F
    return wrapped
}

/** Smallest signed angular difference `a - b`, wrapped to (-PI, PI]. */
fun angleDifference(
    a: Float,
    b: Float,
): Float = wrapAngle(a - b)
