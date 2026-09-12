package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.Location

/**
 * The [SensorModule] implementation for the city domain: given the cell the
 * explorer just moved to, reports that cell's [LabelFeature] straight from
 * [cityMap]. No segmentation step is needed the way
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] needs one for a continuous
 * touch stroke — a city move already arrives as one discrete, already-
 * resolved observation, the same way real Monty's own `SensorModule`s vary
 * per-domain (e.g. a depth-camera SM needing multi-point surface fitting
 * vs. one that reads a single resolved value straight off its sensor).
 *
 * [LabelFeature.Companion.EMPTY] cells report `passMessage = false`: an empty cell
 * carries no identity-defining information, so it's a "nothing new this
 * step" observation the same way [SensorModule]'s own contract already
 * models one — never a real graph node. This matters for an automated
 * search over a whole grid ([com.eta.tbp.lib.lm.Experiment]): most cells in a real city
 * are empty, and without this, an empty cell landing anywhere in the
 * observed sequence would force every taught city to score zero (nothing
 * taught has an "empty" node either), not just fail to help.
 *
 * [logger] defaults to [com.eta.tbp.lib.log.Logger.Console] (silent), same as every other class in
 * this pipeline
 */
class EnvironmentSensorModule(
    override val sensorId: String,
    private val environment: Environment,
    private val logger: Logger = Logger.Console,
) : SensorModule {
    override fun step(observation: Location): CmpMessage {
        val feature = environment.featureAt(observation)
        val passMessage = feature != null
        logger.debug(TAG) { "[$sensorId] $observation -> '${feature?.label}' (passMessage=$passMessage)" }
        return CmpMessage(
            location = observation,
            feature = feature,
            confidence = 1f,
            passMessage = passMessage,
            senderId = sensorId,
            senderType = SenderType.SM,
            processFeaturesInLm = true,
        )
    }

    override fun preEpisode() = Unit

    override fun postEpisode() = Unit

    private companion object {
        const val TAG = "EnvironmentSensorModule"
    }
}
