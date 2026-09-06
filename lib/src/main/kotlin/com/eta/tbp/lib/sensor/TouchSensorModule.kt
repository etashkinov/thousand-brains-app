package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import kotlin.math.cos
import kotlin.math.sin

interface StrokeFeatures {
    val curvature: Float
    val strokeIndex: Int
    val orderInStroke: Int
}

/**
 * Converts already-resampled [RawTouchObservation]s (produced upstream by
 * [StrokePreprocessor]) into [CmpMessage]s. Stateless: resampling already
 * happened before [step] is ever called, so there's nothing to carry
 * between steps or reset between episodes.
 */
class TouchSensorModule(
    override val sensorId: String,
) : SensorModule<RawTouchObservation> {
    override fun step(observation: RawTouchObservation): CmpMessage =
        CmpMessage(
            location = observation.position.copyOf(),
            morphologicalFeatures =
                MorphologicalFeatures(
                    poseVectors = tangentPoseVectors(observation.tangentAngle),
                    // Every resampled point already carries a tangent angle (even a
                    // single-point stroke gets one via StrokePreprocessor's fallback),
                    // so pose is always well-defined in v1 — no ambiguous-pose case yet.
                    poseFullyDefined = true,
                ),
            nonMorphologicalFeatures =
                object : StrokeFeatures {
                    override val curvature: Float = observation.curvature
                    override val strokeIndex: Int = observation.strokeIndex
                    override val orderInStroke: Int = observation.orderInStroke
                },
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
