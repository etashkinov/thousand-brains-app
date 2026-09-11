package com.eta.tbp.lib.city

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.sensor.SensorModule

/**
 * The `SensorModule<MapLocation>` for the city domain: given the cell the
 * explorer just moved to, reports that cell's [MapFeature] straight from
 * [cityMap]. No segmentation step is needed the way
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] needs one for a continuous
 * touch stroke — a city move already arrives as one discrete, already-
 * resolved observation, the same way real Monty's own `SensorModule`s vary
 * per-domain (e.g. a depth-camera SM needing multi-point surface fitting
 * vs. one that reads a single resolved value straight off its sensor).
 */
class CitySensorModule(
    override val sensorId: String,
    private val cityMap: CityMap,
) : SensorModule<MapLocation> {
    override fun step(observation: MapLocation): CmpMessage =
        CmpMessage(
            location = observation,
            feature = cityMap.featureAt(observation),
            confidence = 1f,
            passMessage = true,
            senderId = sensorId,
            senderType = SenderType.SM,
            processFeaturesInLm = true,
        )

    override fun preEpisode() = Unit

    override fun postEpisode() = Unit
}
