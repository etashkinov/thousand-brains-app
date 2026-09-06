package com.eta.tbp.lib.cmp

/** Mirrors Monty CMP's sender kinds: a Sensor Module or a Learning Module. */
enum class SenderType { SM, LM }

/**
 * Mirrors Monty's morphological-features payload: pose vectors plus whether
 * the pose is fully determined yet. 2x2 in our 2D domain (Monty: 3x3 for 3D).
 */
data class MorphologicalFeatures(
    val poseVectors: Array<FloatArray>,
    val poseFullyDefined: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MorphologicalFeatures

        if (poseFullyDefined != other.poseFullyDefined) return false
        if (!poseVectors.contentDeepEquals(other.poseVectors)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = poseFullyDefined.hashCode()
        result = 31 * result + poseVectors.contentDeepHashCode()
        return result
    }
}

/**
 * Mirrors `tbp.monty.cmp.Message` — the single message format exchanged
 * between every Sensor/Learning Module, feed-forward or lateral (voting).
 */
open class CmpMessage(
    val location: FloatArray?,
    val morphologicalFeatures: MorphologicalFeatures?,
    val nonMorphologicalFeatures: Any,
    val confidence: Float,
    val passMessage: Boolean,
    val senderId: String,
    val senderType: SenderType,
    val processFeaturesInLm: Boolean,
) {
    fun getPoseVectors(): Array<FloatArray>? = morphologicalFeatures?.poseVectors

    fun isFromSm(): Boolean = senderType == SenderType.SM
}

/**
 * Mirrors `tbp.monty.cmp.Goal` — used by Phase-8 hypothesis-directed glide
 * branching. Present as a class shape but unused until then.
 */
class CmpGoal(
    location: FloatArray?,
    morphologicalFeatures: MorphologicalFeatures?,
    nonMorphologicalFeatures: Any,
    confidence: Float,
    passMessage: Boolean,
    senderId: String,
    senderType: SenderType,
    processFeaturesInLm: Boolean,
    val goalTolerances: Map<String, Any>?,
    val info: Map<String, Any>? = null,
) : CmpMessage(
        location,
        morphologicalFeatures,
        nonMorphologicalFeatures,
        confidence,
        passMessage,
        senderId,
        senderType,
        processFeaturesInLm,
    )
