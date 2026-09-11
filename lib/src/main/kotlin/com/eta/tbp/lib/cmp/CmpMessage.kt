package com.eta.tbp.lib.cmp

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.Location

/** Mirrors Monty CMP's sender kinds: a Sensor Module or a Learning Module. */
enum class SenderType { SM, LM }

/**
 * Mirrors `tbp.monty.cmp.Message` — the single message format exchanged
 * between every Sensor/Learning Module, feed-forward or lateral (voting).
 * [location]/[feature] carry whatever payload the producer's domain needs —
 * a stroke's [com.eta.tbp.lib.sensor.FloatLocation]/
 * [com.eta.tbp.lib.sensor.PrimitiveFeature] or a city's
 * [com.eta.tbp.lib.city.MapLocation]/[com.eta.tbp.lib.city.MapFeature] — via
 * the generic [Location]/[Feature] interfaces, rather than this class
 * knowing about pose vectors or any other domain-specific shape directly.
 */
open class CmpMessage(
    val location: Location?,
    val feature: Feature?,
    val confidence: Float,
    val passMessage: Boolean,
    val senderId: String,
    val senderType: SenderType,
    val processFeaturesInLm: Boolean,
) {
    fun isFromSm(): Boolean = senderType == SenderType.SM
}

/**
 * Mirrors `tbp.monty.cmp.Goal` — used by Phase-8 hypothesis-directed glide
 * branching. Present as a class shape but unused until then.
 */
class CmpGoal(
    location: Location?,
    feature: Feature?,
    confidence: Float,
    passMessage: Boolean,
    senderId: String,
    senderType: SenderType,
    processFeaturesInLm: Boolean,
    val goalTolerances: Map<String, Any>?,
    val info: Map<String, Any>? = null,
) : CmpMessage(
        location,
        feature,
        confidence,
        passMessage,
        senderId,
        senderType,
        processFeaturesInLm,
    )
