package com.eta.tbp.lib.sensor

import kotlin.math.sqrt

/**
 * Plain 2D point/vector — the Kotlin data type that crosses the `lib`/`app`
 * boundary for touch geometry. Not a Monty-mirrored concept; `app` maps
 * Compose's `Offset` to this before calling into `lib`.
 */
data class RawPoint(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: RawPoint): RawPoint = RawPoint(x + other.x, y + other.y)

    operator fun minus(other: RawPoint): RawPoint = RawPoint(x - other.x, y - other.y)

    operator fun times(scalar: Float): RawPoint = RawPoint(x * scalar, y * scalar)

    fun length(): Float = sqrt(x * x + y * y)

    fun toFloatArray(): FloatArray = floatArrayOf(x, y)
}
