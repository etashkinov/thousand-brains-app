package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage

/** Mirrors `abstract_monty_classes.SensorModule`. */
interface SensorModule {
    val sensorId: String

    fun step(rawObservation: RawTouchObservation): CmpMessage

    fun preEpisode()

    fun postEpisode()
}
