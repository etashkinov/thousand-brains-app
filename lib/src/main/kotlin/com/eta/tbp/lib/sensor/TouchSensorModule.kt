package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Converts already-resampled [RawTouchObservation]s (produced upstream by
 * [StrokePreprocessor]) into [CmpMessage]s. Stateless: resampling already
 * happened before [step] is ever called, so there's nothing to carry
 * between steps or reset between episodes.
 */
class TouchSensorModule(
    override val sensorId: String,
) : SensorModule {
    override fun step(rawObservation: RawTouchObservation): CmpMessage =
        CmpMessage(
            location = rawObservation.position.copyOf(),
            morphologicalFeatures =
                MorphologicalFeatures(
                    poseVectors = tangentPoseVectors(rawObservation.tangentAngle),
                    // Every resampled point already carries a tangent angle (even a
                    // single-point stroke gets one via StrokePreprocessor's fallback),
                    // so pose is always well-defined in v1 — no ambiguous-pose case yet.
                    poseFullyDefined = true,
                ),
            nonMorphologicalFeatures =
                mapOf(
                    "curvature" to rawObservation.curvature,
                    "stroke_index" to rawObservation.strokeIndex,
                    "order_in_stroke" to rawObservation.orderInStroke,
                ),
            confidence = 1f,
            passMessage = true,
            senderId = sensorId,
            senderType = SenderType.SM,
            processFeaturesInLm = true,
        )

    override fun preEpisode() {}

    override fun postEpisode() {}

    /** 2x2 rotation basis: first row is the tangent direction, second the normal. */
    private fun tangentPoseVectors(tangentAngle: Float): Array<FloatArray> {
        val cosA = cos(tangentAngle)
        val sinA = sin(tangentAngle)
        return arrayOf(
            floatArrayOf(cosA, sinA),
            floatArrayOf(-sinA, cosA),
        )
    }
}
