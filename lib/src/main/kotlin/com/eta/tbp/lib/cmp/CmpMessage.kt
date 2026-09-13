package com.eta.tbp.lib.cmp

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.Location

/**
 * Mirrors Monty CMP's sender kinds: a Sensor Module or a Learning Module —
 * plus [GSG] (Goal State Generator), the only sender kind a [CmpGoal] may
 * carry (mirrors `cmp.Goal._set_allowable_sender_types` returning `("GSG",
 * "SM")`, not plain `Message`'s own `("SM", "LM")`). An LM's embedded goal
 * generator (e.g. [com.eta.tbp.lib.lm.EvidenceGraphLM.proposeGoal]) sends
 * as [GSG], never [LM].
 */
enum class SenderType { SM, LM, GSG }

/**
 * Mirrors `tbp.monty.cmp.Message` — the single message format exchanged
 * between every Sensor/Learning Module, feed-forward or lateral (voting).
 * [location]/[feature] carry whatever payload the producer's domain needs —
 * a stroke's [com.eta.tbp.lib.sensor.FloatLocation]/
 * [com.eta.tbp.lib.sensor.PrimitiveFeature] or a city's
 * [com.eta.tbp.lib.memory.LabelFeature] — via
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
 * Mirrors `tbp.monty.cmp.Goal` — what a Goal State Generator sends to the
 * motor system (real Monty: `MotorSystem.__call__`'s `goals: Sequence[Goal]`
 * param; this app: [com.eta.tbp.lib.lm.MotorSystem.nextLocation]'s `goals`
 * param). Live today for the city tier — [com.eta.tbp.lib.lm.EvidenceGraphLM.proposeGoal]
 * is the one producer, [com.eta.tbp.lib.lm.Explorer]'s embedded
 * [com.eta.tbp.lib.lm.MotorSystem] the one consumer — since that tier
 * already has an automated single-LM-to-motor loop real Monty exercises
 * with no voting required. The touch/character tier has no motor system at
 * all (a human draws by hand), so it has no producer or consumer for this
 * class yet; *that* tier's use of [CmpGoal] — voting between sibling LMs'
 * goals during Phase-8 hypothesis-directed glide branching — is still
 * unbuilt.
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
