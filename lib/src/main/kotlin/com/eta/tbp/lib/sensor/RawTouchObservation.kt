package com.eta.tbp.lib.sensor

/**
 * One resampled, normalized point along a stroke — the per-step reading a
 * [SensorModule] consumes. Produced upstream by [StrokePreprocessor], never
 * built directly from a raw touch event (that conversion is `app`'s job).
 */
data class RawTouchObservation(
    val position: FloatArray,
    val tangentAngle: Float,
    val curvature: Float,
    val strokeIndex: Int,
    val orderInStroke: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTouchObservation) return false
        return position.contentEquals(other.position) &&
            tangentAngle == other.tangentAngle &&
            curvature == other.curvature &&
            strokeIndex == other.strokeIndex &&
            orderInStroke == other.orderInStroke
    }

    override fun hashCode(): Int {
        var result = position.contentHashCode()
        result = 31 * result + tangentAngle.hashCode()
        result = 31 * result + curvature.hashCode()
        result = 31 * result + strokeIndex
        result = 31 * result + orderInStroke
        return result
    }
}
